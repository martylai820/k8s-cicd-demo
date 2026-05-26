package com.martylai.smbserver.server

import android.util.Log
import com.martylai.smbserver.data.ServerConfig
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP server that accepts SMB2 client connections.
 * Each accepted connection is handled on a separate thread.
 */
class Smb2Server(private val config: ServerConfig) {

    private val TAG = "Smb2Server"

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private lateinit var handler: Smb2RequestHandler
    private lateinit var fileSystem: AndroidFileSystem

    fun start(): Result<Unit> {
        if (running.get()) return Result.success(Unit)

        val rootFile = File(config.sharePath)
        if (!rootFile.exists()) {
            rootFile.mkdirs()
        }
        if (!rootFile.isDirectory) {
            return Result.failure(IllegalArgumentException("Share path is not a directory: ${config.sharePath}"))
        }

        fileSystem = AndroidFileSystem(rootFile)
        handler = Smb2RequestHandler(config, fileSystem)

        return try {
            val ss = ServerSocket(config.port)
            ss.reuseAddress = true
            serverSocket = ss
            running.set(true)

            // Accept loop on its own thread
            executor.submit {
                Log.i(TAG, "SMB2 server listening on port ${config.port}")
                while (running.get() && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        client.tcpNoDelay = true
                        executor.submit(Smb2Connection(client, handler))
                        Log.d(TAG, "Accepted connection from ${client.remoteSocketAddress}")
                    } catch (e: Exception) {
                        if (running.get()) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
                Log.i(TAG, "Server accept loop ended")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}", e)
            running.set(false)
            Result.failure(e)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(TAG, "Stopping SMB2 server...")
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Don't shutdown executor — reuse it for next start
        Log.i(TAG, "SMB2 server stopped")
    }

    val isRunning: Boolean get() = running.get()
    val port: Int get() = config.port
}

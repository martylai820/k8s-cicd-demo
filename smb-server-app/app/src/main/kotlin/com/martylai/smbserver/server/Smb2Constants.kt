package com.martylai.smbserver.server

/**
 * SMB2 Protocol Constants (MS-SMB2 specification)
 */
object Smb2Constants {

    // Protocol identifier: \xFESMB
    const val SMB2_MAGIC = 0xFE534D42.toInt()
    const val SMB2_HEADER_SIZE = 64

    // ─── Dialect revisions ───────────────────────────────────────────────────
    const val SMB2_DIALECT_2_0_2 = 0x0202
    const val SMB2_DIALECT_2_1   = 0x0210
    const val SMB2_DIALECT_3_0   = 0x0300
    const val SMB2_DIALECT_3_0_2 = 0x0302
    const val SMB2_DIALECT_3_1_1 = 0x0311

    // ─── Command codes ───────────────────────────────────────────────────────
    const val CMD_NEGOTIATE        = 0x0000.toShort()
    const val CMD_SESSION_SETUP    = 0x0001.toShort()
    const val CMD_LOGOFF           = 0x0002.toShort()
    const val CMD_TREE_CONNECT     = 0x0003.toShort()
    const val CMD_TREE_DISCONNECT  = 0x0004.toShort()
    const val CMD_CREATE           = 0x0005.toShort()
    const val CMD_CLOSE            = 0x0006.toShort()
    const val CMD_FLUSH            = 0x0007.toShort()
    const val CMD_READ             = 0x0008.toShort()
    const val CMD_WRITE            = 0x0009.toShort()
    const val CMD_LOCK             = 0x000A.toShort()
    const val CMD_IOCTL            = 0x000B.toShort()
    const val CMD_CANCEL           = 0x000C.toShort()
    const val CMD_ECHO             = 0x000D.toShort()
    const val CMD_QUERY_DIRECTORY  = 0x000E.toShort()
    const val CMD_CHANGE_NOTIFY    = 0x000F.toShort()
    const val CMD_QUERY_INFO       = 0x0010.toShort()
    const val CMD_SET_INFO         = 0x0011.toShort()
    const val CMD_OPLOCK_BREAK     = 0x0012.toShort()

    // ─── Header flags ────────────────────────────────────────────────────────
    const val FLAGS_SERVER_TO_REDIR = 0x00000001
    const val FLAGS_ASYNC_COMMAND   = 0x00000002
    const val FLAGS_SIGNED          = 0x00000008

    // ─── NTSTATUS codes ──────────────────────────────────────────────────────
    const val STATUS_SUCCESS                 = 0x00000000L
    const val STATUS_MORE_PROCESSING         = 0xC0000016L
    const val STATUS_ACCESS_DENIED          = 0xC0000022L
    const val STATUS_NO_SUCH_FILE           = 0xC000000FL
    const val STATUS_OBJECT_NAME_NOT_FOUND  = 0xC0000034L
    const val STATUS_OBJECT_PATH_NOT_FOUND  = 0xC000003AL
    const val STATUS_END_OF_FILE            = 0xC0000011L
    const val STATUS_NO_MORE_FILES          = 0x80000006L
    const val STATUS_SHARING_VIOLATION      = 0xC0000043L
    const val STATUS_INVALID_PARAMETER      = 0xC000000DL
    const val STATUS_NOT_SUPPORTED          = 0xC00000BBL
    const val STATUS_LOGON_FAILURE          = 0xC000006DL
    const val STATUS_WRONG_PASSWORD         = 0xC000006AL
    const val STATUS_INVALID_HANDLE         = 0xC0000008L
    const val STATUS_DELETE_PENDING         = 0xC0000056L
    const val STATUS_NOT_IMPLEMENTED        = 0xC0000002L
    const val STATUS_BUFFER_TOO_SMALL       = 0xC0000023L
    const val STATUS_INFO_LENGTH_MISMATCH   = 0xC0000004L
    const val STATUS_INSUFFICIENT_RESOURCES = 0xC000009AL
    const val STATUS_FILE_IS_A_DIRECTORY    = 0xC00000BAL
    const val STATUS_NOT_A_DIRECTORY        = 0xC0000103L
    const val STATUS_DISK_FULL              = 0xC000007FL
    const val STATUS_NETWORK_NAME_DELETED   = 0xC00000C9L

    // ─── Security modes ──────────────────────────────────────────────────────
    const val NEGOTIATE_SIGNING_ENABLED  = 0x0001.toShort()
    const val NEGOTIATE_SIGNING_REQUIRED = 0x0002.toShort()

    // ─── Server capabilities ─────────────────────────────────────────────────
    const val CAP_DFS                = 0x00000001
    const val CAP_LEASING            = 0x00000002
    const val CAP_LARGE_MTU          = 0x00000004
    const val CAP_MULTI_CHANNEL      = 0x00000008
    const val CAP_PERSISTENT_HANDLES = 0x00000010
    const val CAP_DIRECTORY_LEASING  = 0x00000020
    const val CAP_ENCRYPTION         = 0x00000040

    // ─── Share types ─────────────────────────────────────────────────────────
    const val SHARE_TYPE_DISK  = 0x01.toByte()
    const val SHARE_TYPE_PIPE  = 0x02.toByte()
    const val SHARE_TYPE_PRINT = 0x03.toByte()

    // ─── Tree Connect flags ──────────────────────────────────────────────────
    const val SHARE_CAP_DFS                = 0x00000008
    const val SHARE_CAP_CONTINUOUS_AVAILABILITY = 0x00000010
    const val SHARE_CAP_SCALEOUT           = 0x00000020
    const val SHARE_CAP_CLUSTER            = 0x00000040

    // ─── File attributes ─────────────────────────────────────────────────────
    const val FILE_ATTRIBUTE_READONLY    = 0x00000001
    const val FILE_ATTRIBUTE_HIDDEN      = 0x00000002
    const val FILE_ATTRIBUTE_SYSTEM      = 0x00000004
    const val FILE_ATTRIBUTE_DIRECTORY   = 0x00000010
    const val FILE_ATTRIBUTE_ARCHIVE     = 0x00000020
    const val FILE_ATTRIBUTE_NORMAL      = 0x00000080
    const val FILE_ATTRIBUTE_TEMPORARY   = 0x00000100

    // ─── CreateDisposition (how to open file) ────────────────────────────────
    const val FILE_SUPERSEDE    = 0
    const val FILE_OPEN         = 1
    const val FILE_CREATE       = 2
    const val FILE_OPEN_IF      = 3
    const val FILE_OVERWRITE    = 4
    const val FILE_OVERWRITE_IF = 5

    // ─── CreateOptions ───────────────────────────────────────────────────────
    const val FILE_DIRECTORY_FILE     = 0x00000001
    const val FILE_NON_DIRECTORY_FILE = 0x00000040
    const val FILE_SYNCHRONOUS_IO_NONALERT = 0x00000020

    // ─── CreateAction (result of CREATE) ─────────────────────────────────────
    const val FILE_SUPERSEDED  = 0
    const val FILE_OPENED      = 1
    const val FILE_CREATED     = 2
    const val FILE_OVERWRITTEN = 3

    // ─── DesiredAccess ───────────────────────────────────────────────────────
    const val FILE_READ_DATA        = 0x00000001
    const val FILE_WRITE_DATA       = 0x00000002
    const val FILE_APPEND_DATA      = 0x00000004
    const val FILE_READ_EA          = 0x00000008
    const val FILE_WRITE_EA         = 0x00000010
    const val FILE_EXECUTE          = 0x00000020
    const val FILE_DELETE_CHILD     = 0x00000040
    const val FILE_READ_ATTRIBUTES  = 0x00000080
    const val FILE_WRITE_ATTRIBUTES = 0x00000100
    const val DELETE                = 0x00010000
    const val READ_CONTROL          = 0x00020000
    const val WRITE_DAC             = 0x00040000
    const val WRITE_OWNER           = 0x00080000
    const val SYNCHRONIZE           = 0x00100000
    const val GENERIC_ALL           = 0x10000000
    const val GENERIC_EXECUTE       = 0x20000000
    const val GENERIC_WRITE         = 0x40000000
    const val GENERIC_READ          = 0x80000000.toInt()

    // ─── FileInformationClass ────────────────────────────────────────────────
    const val FILE_DIRECTORY_INFO         = 1
    const val FILE_FULL_DIR_INFO          = 2
    const val FILE_BOTH_DIR_INFO          = 3
    const val FILE_BASIC_INFO             = 4
    const val FILE_STANDARD_INFO          = 5
    const val FILE_INTERNAL_INFO          = 6
    const val FILE_EA_INFO                = 7
    const val FILE_ACCESS_INFO            = 8
    const val FILE_NAME_INFO              = 9
    const val FILE_RENAME_INFO            = 10
    const val FILE_NAMES_INFO             = 12
    const val FILE_DISPOSITION_INFO       = 13
    const val FILE_POSITION_INFO          = 14
    const val FILE_FULL_EA_INFO           = 15
    const val FILE_MODE_INFO              = 16
    const val FILE_ALIGNMENT_INFO         = 17
    const val FILE_ALL_INFO               = 18
    const val FILE_ALLOCATION_INFO        = 19
    const val FILE_END_OF_FILE_INFO       = 20
    const val FILE_STREAM_INFO            = 22
    const val FILE_PIPE_INFO              = 23
    const val FILE_COMPRESSION_INFO       = 28
    const val FILE_NETWORK_OPEN_INFO      = 34
    const val FILE_ATTRIBUTE_TAG_INFO     = 35
    const val FILE_ID_BOTH_DIR_INFO       = 37
    const val FILE_ID_FULL_DIR_INFO       = 38
    const val FILE_VALID_DATA_LEN_INFO    = 39
    const val FILE_SHORT_NAME_INFO        = 40

    // ─── InfoType ────────────────────────────────────────────────────────────
    const val INFO_FILE       = 0x01.toByte()
    const val INFO_FILESYSTEM = 0x02.toByte()
    const val INFO_SECURITY   = 0x03.toByte()
    const val INFO_QUOTA      = 0x04.toByte()

    // ─── FilesystemInfoClass ─────────────────────────────────────────────────
    const val FS_VOLUME_INFO          = 1
    const val FS_LABEL_INFO           = 2
    const val FS_SIZE_INFO            = 3
    const val FS_DEVICE_INFO          = 4
    const val FS_ATTRIBUTE_INFO       = 5
    const val FS_CONTROL_INFO         = 6
    const val FS_FULL_SIZE_INFO       = 7
    const val FS_OBJECT_ID_INFO       = 8
    const val FS_DRIVER_PATH_INFO     = 9
    const val FS_SECTOR_SIZE_INFO     = 11

    // ─── Windows FILETIME epoch offset ───────────────────────────────────────
    // Number of 100-nanosecond intervals between 1601-01-01 and 1970-01-01
    const val WINDOWS_TIME_OFFSET = 116444736000000000L

    // Convert Unix epoch millis to Windows FILETIME (100-ns intervals)
    fun toWindowsTime(unixMillis: Long): Long =
        (unixMillis + 11644473600000L) * 10000L

    // Convert Windows FILETIME to Unix epoch millis
    fun toUnixMillis(windowsTime: Long): Long =
        windowsTime / 10000L - 11644473600000L

    // Max transfer sizes
    const val MAX_READ_SIZE  = 1 * 1024 * 1024  // 1 MB
    const val MAX_WRITE_SIZE = 1 * 1024 * 1024  // 1 MB
    const val MAX_TRANS_SIZE = 1 * 1024 * 1024  // 1 MB
}

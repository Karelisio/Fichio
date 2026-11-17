// File service running in the Shizuku/Shevery process (shell or root uid), giving Fichio
// access to Android/data and Android/obb. Listings are encoded by FileServiceCodec.
package com.karelisio.fichio.shizuku;

interface IFileService {
    /** Reserved by Shizuku: called when the UserService is destroyed. */
    void destroy() = 16777114;

    /** Terminates the service process. */
    void exit() = 1;

    /** One page of a directory listing (see FileServiceCodec.encodeListing). */
    String list(String path, int offset, int limit) = 2;

    /** Encoded entry, or null when the path does not exist. */
    String stat(String path) = 3;

    ParcelFileDescriptor openRead(String path) = 4;

    /** Opens for writing, creating parent directories. */
    ParcelFileDescriptor openWrite(String path, boolean append) = 5;

    boolean mkdirs(String path) = 6;

    boolean createFile(String path) = 7;

    boolean deleteSingle(String path) = 8;

    boolean deleteRecursively(String path) = 9;

    boolean rename(String from, String to) = 10;

    boolean setLastModified(String path, long time) = 11;

    long freeSpace(String path) = 12;
}

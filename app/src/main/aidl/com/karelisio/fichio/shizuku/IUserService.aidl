package com.karelisio.fichio.shizuku;

/**
 * AIDL interface exposed by the Shevery/Shizuku UserService for accessing
 * Android/data and Android/obb, which are otherwise unreachable on Android 11+.
 *
 * AIDL requires that either every method or no method carries an explicit
 * transaction id, so all of them are numbered here. The reserved destroy
 * method MUST use 16777114 as required by the Shizuku/Shevery server.
 */
interface IUserService {
    void destroy() = 16777114;
    String ping() = 1;
    String[] listFiles(String path) = 2;
    boolean isDirectory(String path) = 3;
    boolean exists(String path) = 4;
    boolean copy(String from, String to) = 5;
    boolean move(String from, String to) = 6;
    boolean delete(String path) = 7;
    boolean mkdir(String path) = 8;
    boolean rename(String from, String to) = 9;
    long length(String path) = 10;
    long lastModified(String path) = 11;
}

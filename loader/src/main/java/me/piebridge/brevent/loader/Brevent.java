package me.piebridge.brevent.loader;

import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dalvik.system.PathClassLoader;
import me.piebridge.brevent.server.HideApiOverride;
import me.piebridge.brevent.server.HideApiOverrideN;

/**
 * Brevent Shell entry
 * <p>
 * Created by thom on 2016/11/22.
 */
public class Brevent implements Runnable {

    private static final String TAG = "BreventLoader";

    private static final int BUFFER = 0x2000;

    private static final String BREVENT_PACKAGE = "me.piebridge.brevent";

    private static final String BREVENT_CLASS = "me.piebridge.brevent.server.BreventServer";

    private static final String LIB_READER = "lib" + "reader" + ".so";

    private static final String LIB_LOADER = "lib" + "loader" + ".so";

    private static final int USER_OWNER = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? HideApiOverrideN.USER_SYSTEM : HideApiOverride.USER_OWNER;

    private static final int MIN_SURVIVE_TIME = 30;

    private final Method mMain;

    private final CountDownLatch mLatch;

    public Brevent(Method main, CountDownLatch latch) {
        mMain = main;
        mLatch = latch;
    }

    @Override
    public void run() {
        try {
            mMain.invoke(null, (Object) null);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Can't run brevent server", e);
            throw new RuntimeException(e);
        } finally {
            mLatch.countDown();
        }
    }

    private static String getDataDir(IPackageManager packageManager) throws RemoteException {
        int uid = HideApiOverride.uidForData(Process.myUid());
        String[] packageNames = getPackagesForUid(packageManager, uid);
        if (packageNames != null) {
            for (String packageName : packageNames) {
                String dataDir = getPackageInfo(packageManager, packageName, 0, USER_OWNER).applicationInfo.dataDir;
                if (dataDir != null) {
                    return dataDir;
                }
            }
        }
        String message = "Can't find package for " + uid;
        Log.e(TAG, message);
        throw new UnsupportedOperationException(message);
    }

    private static File copyFile(File from, File to, String name) throws IOException {
        if (!to.isDirectory() && !to.mkdirs()) {
            String message = "Can't make sure directory: " + to;
            Log.w(TAG, message);
            throw new UnsupportedOperationException(message);
        }
        File input = new File(from, name);
        File output = new File(to, name);
        try (
                InputStream is = new FileInputStream(input);
                OutputStream os = new FileOutputStream(output)
        ) {
            int length;
            byte[] bytes = new byte[BUFFER];
            while ((length = is.read(bytes, 0, BUFFER)) != -1) {
                os.write(bytes, 0, length);
            }
            return output;
        }
    }

    private static String[] getPackagesForUid(IPackageManager packageManager, int uid) throws RemoteException {
        return packageManager.getPackagesForUid(uid);
    }

    private static PackageInfo getPackageInfo(IPackageManager packageManager, String packageName, int flags, int userId) throws RemoteException {
        return packageManager.getPackageInfo(packageName, flags, userId);
    }

    public static void main(String[] args) throws Exception {
        IPackageManager packageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (packageManager == null) {
            Log.e(TAG, "Could not access the Package Manager. Is the system running?");
            System.exit(1);
        }
        PackageInfo packageInfo = getPackageInfo(packageManager, BREVENT_PACKAGE, 0, USER_OWNER);
        File nativeLibraryDir = new File(packageInfo.applicationInfo.nativeLibraryDir);
        File libDir = new File(getDataDir(packageManager), "brevent");
        File libReader = copyFile(nativeLibraryDir, libDir, LIB_READER);
        File libLoader = copyFile(nativeLibraryDir, libDir, LIB_LOADER);
        Log.i(TAG, "lib: " + libDir + ", loader: " + libLoader);
        ClassLoader bootClassLoader = ClassLoader.getSystemClassLoader().getParent();
        ClassLoader loadClassLoader = new PathClassLoader(libLoader.getAbsolutePath(), libDir.getAbsolutePath(), bootClassLoader);
        long previous = System.currentTimeMillis();
        ClassLoader classLoader = new PathClassLoader(packageInfo.applicationInfo.sourceDir, loadClassLoader);
        Method main = classLoader.loadClass(BREVENT_CLASS).getMethod("main", String[].class);
        CountDownLatch latch = new CountDownLatch(0x1);
        new Thread(new Brevent(main, latch)).start();
        latch.await();
        long now = System.currentTimeMillis();
        if (TimeUnit.MILLISECONDS.toSeconds(now - previous) < MIN_SURVIVE_TIME) {
            Log.e(TAG, "Brevent Server quit in " + MIN_SURVIVE_TIME + " seconds, quit");
            System.exit(1);
        }
        if (packageInfo == null) {
            if (!libLoader.delete() || !libReader.delete() || !libDir.delete()) {
                Log.w(TAG, "Can't remove brevent loader");
            }
        }
    }

}

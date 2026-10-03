package org.ghostcloak.app;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Test APK service under the instrumentation package UID. No Kotlin runtime is needed. */
public final class RetainedProbeService extends Service {
    public static final String TOKEN = "org.ghostcloak.app.test.retained-probe";
    public static final int OPEN = IBinder.FIRST_CALL_TRANSACTION;
    public static final int EXISTS = OPEN + 1;
    public static final int CLEAR = OPEN + 2;
    public static final Set<String> NAMES = new HashSet<>(Arrays.asList(
        "IMAGE.cipher", "DOCUMENT.cipher", "local.db", "local.db-wal",
        "local.db-shm", "local.wrapped", "verified"));

    private File directory() {
        File root = new File(getFilesDir(), "safe-exit-probes");
        if ((!root.mkdirs() && !root.isDirectory()) || Files.isSymbolicLink(root.toPath()))
            throw new SecurityException("probe_parent_unavailable");
        return root;
    }

    @Override public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws android.os.RemoteException {
                String[] callers = getPackageManager().getPackagesForUid(getCallingUid());
                if (callers == null || !Arrays.asList(callers).contains("org.ghostcloak.app") ||
                    getPackageManager().checkSignatures("org.ghostcloak.app", getPackageName())
                        != PackageManager.SIGNATURE_MATCH) throw new SecurityException("probe_caller");
                data.enforceInterface(TOKEN);
                if (reply == null) throw new SecurityException("probe_reply");
                File root = directory();
                if (code == CLEAR) {
                    File[] files = root.listFiles();
                    if (files == null) throw new SecurityException("probe_cleanup");
                    for (File file : files) if (!file.isFile() || !file.delete())
                        throw new SecurityException("probe_cleanup");
                    reply.writeNoException();
                    return true;
                }
                String name = data.readString();
                if (!NAMES.contains(name)) throw new SecurityException("probe_name");
                File file = new File(root, name);
                try {
                    if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))
                        throw new SecurityException("probe_path");
                } catch (IOException failure) { throw new SecurityException("probe_path", failure); }
                if (code == OPEN) {
                    boolean writing = data.readInt() == 1;
                    int mode = writing ? ParcelFileDescriptor.MODE_WRITE_ONLY |
                        ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                        : ParcelFileDescriptor.MODE_READ_ONLY;
                    try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, mode)) {
                        reply.writeNoException();
                        reply.writeParcelable(descriptor, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    } catch (IOException failure) { throw new SecurityException("probe_io", failure); }
                    return true;
                }
                if (code == EXISTS) {
                    reply.writeNoException();
                    reply.writeInt(file.isFile() ? 1 : 0);
                    return true;
                }
                throw new SecurityException("probe_operation");
            }
        };
    }
}

package com.itsluminous.clearmovie.share;

import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Another app's files, as a gallery or chat app shares them (ShareDeviceTest).
 * It runs in the test package's own process and uid; only a caller holding
 * DUMP (the shell) may read it directly, so ClearMovie can open a file only
 * through the grant a share carries.
 *
 * <p>Written in Java on purpose: this process has the test APK only, and the
 * Kotlin standard library lives in the app's APK.
 *
 * <p>A file's name is {@code <tag>~<asset>}: the asset is one of the test
 * fixtures, and a tag {@code t<seconds>} gives it that capture time.
 * {@code doc.pdf} and {@code note.txt} are not media. {@link #call} "share"
 * builds the share as the sharing app does (a PendingIntent it owns, so the
 * grant is the sharing app's), and "revoke" takes every grant on the named
 * files back.
 */
public class SharingAppProvider extends ContentProvider {
    public static final String AUTHORITY = "com.itsluminous.sharetest.files";
    public static final String METHOD_SHARE = "share";
    public static final String METHOD_REVOKE = "revoke";
    public static final String KEY_NAMES = "names";
    public static final String KEY_TARGET = "target";
    public static final String KEY_ACTION = "action";
    public static final String KEY_GRANT = "grant";
    public static final String KEY_PENDING = "pending";

    private static final AtomicInteger REQUESTS = new AtomicInteger();

    public static Uri uriOf(String name) {
        return Uri.parse("content://" + AUTHORITY + "/f/").buildUpon().appendPath(name).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private byte[] bytesOf(String name) throws IOException {
        if (name.equals("doc.pdf")) return "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        if (name.equals("note.txt")) return "hello".getBytes(StandardCharsets.US_ASCII);
        String asset = name.contains("~") ? name.substring(name.indexOf('~') + 1) : name;
        try (InputStream in = getContext().getAssets().open("fixtures/" + asset)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static String mimeOf(String name) {
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "text/plain";
    }

    private static Long takenOf(String name) {
        if (!name.startsWith("t") || !name.contains("~")) return null;
        try {
            return Long.parseLong(name.substring(1, name.indexOf('~'))) * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String name = uri.getLastPathSegment();
        if (name == null) return null;
        String[] columns =
                projection != null
                        ? projection
                        : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN};
        long size;
        try {
            size = bytesOf(name).length;
        } catch (IOException e) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case OpenableColumns.DISPLAY_NAME:
                    row[i] = name;
                    break;
                case OpenableColumns.SIZE:
                    row[i] = size;
                    break;
                case MediaStore.MediaColumns.DATE_TAKEN:
                    row[i] = takenOf(name);
                    break;
                default:
                    row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return name == null ? null : mimeOf(name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null) throw new FileNotFoundException(uri.toString());
        File file = new File(getContext().getCacheDir(), "served-" + name.replace('~', '_'));
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytesOf(name));
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> names = extras == null ? null : extras.getStringArrayList(KEY_NAMES);
        if (names != null) for (String name : names) uris.add(uriOf(name));
        if (METHOD_SHARE.equals(method)) {
            String target = extras.getString(KEY_TARGET);
            String action = extras.getString(KEY_ACTION, Intent.ACTION_SEND_MULTIPLE);
            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName(target, "com.itsluminous.clearmovie.ShareActivity"));
            intent.setType("*/*");
            if (Intent.ACTION_SEND.equals(action)) {
                if (!uris.isEmpty()) intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            if (extras.getBoolean(KEY_GRANT, true)) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (!uris.isEmpty()) {
                // A ClipData set by the sender is left alone, so no read grant is added for it: links with no grant.
                ClipData clip = ClipData.newRawUri(null, uris.get(0));
                for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                intent.setClipData(clip);
            }
            // Otherwise PendingIntent.getActivity moves EXTRA_STREAM into the ClipData with a read grant, as a real share does.
            PendingIntent pending =
                    PendingIntent.getActivity(
                            context,
                            REQUESTS.incrementAndGet(),
                            intent,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Bundle result = new Bundle();
            result.putParcelable(KEY_PENDING, pending);
            return result;
        }
        if (METHOD_REVOKE.equals(method)) {
            for (Uri uri : uris) context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return new Bundle();
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

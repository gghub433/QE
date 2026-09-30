package lv.budseq;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Замена FileProvider без AndroidX: отдаёт другим приложениям (Instagram, Telegram…) только файлы
 * из cache/share, только на чтение и только по выданному разрешению (grantUriPermissions).
 */
public class ShareProvider extends ContentProvider {
    public static final String AUTHORITY = "lv.budseq.share";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        // только имя файла: никаких «..» и вложенных путей
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("..")
                || uri.getPathSegments().size() != 1) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(new File(getContext().getCacheDir(), "share"), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.equals("r")) throw new SecurityException("read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        return n != null && n.endsWith(".png") ? "image/png" : "application/octet-stream";
    }

    /** Имя и размер — их спрашивают приложения, куда отправляют картинку. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = file(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}

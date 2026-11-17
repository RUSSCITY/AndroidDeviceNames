package com.jaredrummler.android.device;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import com.jaredrummler.android.device.DeviceName.DeviceInfo;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Database helper to access the list of all known Android devices with their market, code, and
 * model name.
 */
public class DeviceDatabase extends SQLiteOpenHelper {

  private static final String TABLE_DEVICES = "devices";

  private static final String COLUMN_NAME = "name";
  private static final String COLUMN_CODENAME = "codename";
  private static final String COLUMN_MODEL = "model";

  private static final String NAME = "android-devices.db";
  /**
   * Version of the bundled database asset. Increase this whenever the asset in
   * {@code src/main/assets} is updated, so existing installations replace their copy.
   */
  private static final int VERSION = 2;

  private final File file;
  private final Context context;

  @SuppressWarnings("WeakerAccess")
  public DeviceDatabase(Context context, boolean refresh) {
    super(context, NAME, null, VERSION);
    this.context = context.getApplicationContext();
    this.file = context.getDatabasePath(NAME);
    // The version check happens here, before SQLiteOpenHelper opens the file, because replacing
    // the database from inside onUpgrade() is not possible (the helper is mid-open there).
    if (refresh || !file.exists() || getInstalledVersion() != VERSION) {
      create();
    }
  }

  /**
   * Query the market name given the codename and/or model of a device.
   *
   * @param codename the value of the system property "ro.product.device" ({@link Build#DEVICE}).
   * @param model the value of the system property "ro.product.model" ({@link Build#MODEL}).
   * @return The market name of the device if it is found in the database, otherwise null.
   */
  public String query(@Nullable String codename, @Nullable String model) {
    SQLiteDatabase database = getReadableDatabase();

    String[] columns = new String[] { COLUMN_NAME };
    String selection;
    String[] selectionArgs;
    if (codename != null && model != null) {
      selection = COLUMN_CODENAME + " LIKE ? OR " + COLUMN_MODEL + " LIKE ?";
      selectionArgs = new String[] { codename, model };
    } else if (codename != null) {
      selection = COLUMN_CODENAME + " LIKE ?";
      selectionArgs = new String[] { codename };
    } else if (model != null) {
      selection = COLUMN_MODEL + " LIKE ?";
      selectionArgs = new String[] { model };
    } else {
      return null;
    }

    Cursor cursor =
        database.query(TABLE_DEVICES, columns, selection, selectionArgs, null, null, null);

    String name = null;
    if (cursor.moveToFirst()) {
      name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME));
    }

    close(cursor);
    close(database);

    return name;
  }

  /**
   * Query the device info given the codename and/or model of a device.
   *
   * @param codename the value of the system property "ro.product.device" ({@link Build#DEVICE}).
   * @param model the value of the system property "ro.product.model" ({@link Build#MODEL}).
   * @return The {@link DeviceInfo} if it is found in the database, otherwise null.
   */
  @SuppressWarnings("WeakerAccess")
  public DeviceInfo queryToDevice(@Nullable String codename, @Nullable String model) {
    SQLiteDatabase database = getReadableDatabase();

    String[] columns = new String[]{COLUMN_NAME, COLUMN_CODENAME, COLUMN_MODEL};
    String selection;
    String[] selectionArgs;

    if (!TextUtils.isEmpty(codename) && !TextUtils.isEmpty(model)) {
      // MY MODIFICATIONS: FIRST TRY WITH AND
      selection = COLUMN_CODENAME + " LIKE ? AND " + COLUMN_MODEL + " LIKE ?";
      selectionArgs = new String[]{codename, model};
    } else if (!TextUtils.isEmpty(codename)) {
      selection = COLUMN_CODENAME + " LIKE ?";
      selectionArgs = new String[]{codename};
    } else if (!TextUtils.isEmpty(model)) {
      selection = COLUMN_MODEL + " LIKE ?";
      selectionArgs = new String[]{model};
    } else {
      return null;
    }

    Cursor cursor =
            database.query(TABLE_DEVICES, columns, selection, selectionArgs, null, null, null);

    DeviceInfo deviceInfo = null;

    if (cursor.moveToFirst()) {
      String name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME));
      codename = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CODENAME));
      model = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MODEL));
      deviceInfo = new DeviceInfo(name, codename, model);
    } else if (!TextUtils.isEmpty(codename) && !TextUtils.isEmpty(model)) {
      // MY MODIFICATIONS: SECOND TRY WITH OR
      selection = COLUMN_CODENAME + " LIKE ? OR " + COLUMN_MODEL + " LIKE ?";
      selectionArgs = new String[]{codename, model};
      cursor =
              database.query(TABLE_DEVICES, columns, selection, selectionArgs, null, null, null);

      if (cursor.moveToFirst()) {
        String name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME));
        codename = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CODENAME));
        model = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MODEL));
        deviceInfo = new DeviceInfo(name, codename, model);
      }
    }

    close(cursor);
    close(database);

    return deviceInfo;
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    // no-op
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    // no-op: outdated databases are replaced in the constructor before they are opened.
  }

  @Override
  public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    // no-op: the default implementation throws; the constructor already replaced the file.
  }

  /** @return the user_version of the installed database file, or -1 if it cannot be read. */
  private int getInstalledVersion() {
    SQLiteDatabase db = null;
    try {
      db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
      return db.getVersion();
    } catch (Exception e) {
      return -1;
    } finally {
      if (db != null) {
        db.close();
      }
    }
  }

  /** Replace the installed database with the bundled asset and stamp it with {@link #VERSION}. */
  private void create() throws SQLException {
    try {
      close();                       // Close a connection this helper may still hold
      context.deleteDatabase(NAME);  // Also removes -journal/-wal/-shm files of the old copy
      File dir = file.getParentFile();
      if (dir != null && !dir.exists() && !dir.mkdirs()) {
        throw new IOException("Could not create " + dir);
      }
      transferDatabaseAsset();
      SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
          SQLiteDatabase.OPEN_READWRITE);
      try {
        db.setVersion(VERSION);
      } finally {
        db.close();
      }
    } catch (Exception e) {
      throw new SQLException("Error creating " + NAME + " database", e);
    }
  }

  private void transferDatabaseAsset() throws IOException {
    // Copy to a temporary file first so an interrupted copy never leaves a truncated database.
    File tmp = new File(file.getPath() + ".tmp");
    InputStream input = null;
    OutputStream output = null;
    try {
      input = context.getAssets().open(NAME);
      output = new FileOutputStream(tmp);
      byte[] buffer = new byte[8192];
      int length;
      while ((length = input.read(buffer)) > 0) {
        output.write(buffer, 0, length);
      }
      output.flush();
    } finally {
      close(output);
      close(input);
    }
    if (!tmp.renameTo(file)) {
      tmp.delete();
      throw new IOException("Could not move database to " + file);
    }
  }

  private void close(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException ignored) {
      }
    }
  }
}

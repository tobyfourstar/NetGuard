package eu.faircode.netguard;

/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2016 by Marcel Bokhorst (M66B)
*/
import java.util.Collections;
import java.util.LinkedHashSet;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.os.Environment;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.OutputStream;
import java.io.FileWriter;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static eu.faircode.netguard.Util.*;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "NetGuard.Database";

    private static final String DB_NAME = "Netguard";
    private static final int DB_VERSION = 22;

    private static boolean once = true;
    private static List<LogChangedListener> logChangedListeners = new ArrayList<>();
    private static List<AccessChangedListener> accessChangedListeners = new ArrayList<>();
    private static List<ForwardChangedListener> forwardChangedListeners = new ArrayList<>();

    private static HandlerThread hthread = null;
    private static Handler handler = null;

    private final static int MSG_LOG = 1;
    private final static int MSG_ACCESS = 2;
    private final static int MSG_FORWARD = 3;

    private ReentrantReadWriteLock mLock = new ReentrantReadWriteLock(true);

    static {
        hthread = new HandlerThread("DatabaseHelper");
        hthread.start();
        handler = new Handler(hthread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                handleChangedNotification(msg);
            }
        };
    }

    private static DatabaseHelper dh = null;

    public static DatabaseHelper getInstance(Context context) {
        if (dh == null)
            dh = new DatabaseHelper(context.getApplicationContext());
        return dh;
    }

    @Override
    public void close() {
        Log.w(TAG, "Database is being closed");
    }

    private DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);

        if (!once) {
            once = true;

            File dbfile = context.getDatabasePath(DB_NAME);
            if (dbfile.exists()) {
                Log.w(TAG, "Deleting " + dbfile);
                dbfile.delete();
            }

            File dbjournal = context.getDatabasePath(DB_NAME + "-journal");
            if (dbjournal.exists()) {
                Log.w(TAG, "Deleting " + dbjournal);
                dbjournal.delete();
            }
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.i(TAG, "Creating database " + DB_NAME + " version " + DB_VERSION);
        createTableLog(db);
        createTableAccess(db);
        createTableDns(db);
        createTableForward(db);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
        super.onConfigure(db);
    }

    private void createTableLog(SQLiteDatabase db) {
        Log.i(TAG, "Creating log table");
        db.execSQL("CREATE TABLE log (" +
                " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                ", time INTEGER NOT NULL" +
                ", version INTEGER NULL" +
                ", protocol INTEGER NULL" +
                ", flags TEXT" +
                ", saddr TEXT" +
                ", sport INTEGER NULL" +
                ", daddr TEXT" +
                ", dport INTEGER NULL" +
                ", dname TEXT NULL" +
                ", packageName TEXT NULL" +
                ", uid INTEGER NULL" +
                ", data TEXT" +
                ", allowed INTEGER NULL" +
                ", connection INTEGER NULL" +
                ", interactive INTEGER NULL" +
                ");");
        db.execSQL("CREATE INDEX idx_log_time ON log(time)");
        db.execSQL("CREATE INDEX idx_log_dest ON log(daddr)");
        db.execSQL("CREATE INDEX idx_log_dname ON log(dname)");
        db.execSQL("CREATE INDEX idx_log_dport ON log(dport)");
        db.execSQL("CREATE INDEX idx_log_uid ON log(uid)");
    }

    private void createTableAccess(SQLiteDatabase db) {
        Log.i(TAG, "Creating access table");
        db.execSQL("CREATE TABLE access (" +
                " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                ", uid INTEGER NOT NULL" +
                ", version INTEGER NOT NULL" +
                ", protocol INTEGER NOT NULL" +
                ", daddr TEXT NOT NULL" +
                ", dport INTEGER NOT NULL" +
                ", time INTEGER NOT NULL" +
                ", allowed INTEGER NULL" +
                ", block INTEGER NOT NULL" +
                ", sent INTEGER NULL" +
                ", received INTEGER NULL" +
                ", connections INTEGER NULL" +
                ");");
        db.execSQL("CREATE UNIQUE INDEX idx_access ON access(uid, version, protocol, daddr, dport)");
        db.execSQL("CREATE INDEX idx_access_daddr ON access(daddr)");
        db.execSQL("CREATE INDEX idx_access_block ON access(block)");
    }

    private void createTableDns(SQLiteDatabase db) {
        Log.i(TAG, "Creating dns table");
        db.execSQL("CREATE TABLE dns (" +
                " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                ", time INTEGER NOT NULL" +
                ", qname TEXT NOT NULL" +
                ", aname TEXT NOT NULL" +
                ", resource TEXT NOT NULL" +
                ", ttl INTEGER NULL" +
                ");");
        db.execSQL("CREATE UNIQUE INDEX idx_dns ON dns(qname, aname, resource)");
        db.execSQL("CREATE INDEX idx_dns_resource ON dns(resource)");
    }

    private void createTableForward(SQLiteDatabase db) {
        Log.i(TAG, "Creating forward table");
        db.execSQL("CREATE TABLE forward (" +
                " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                ", protocol INTEGER NOT NULL" +
                ", dport INTEGER NOT NULL" +
                ", raddr TEXT NOT NULL" +
                ", rport INTEGER NOT NULL" +
                ", ruid INTEGER NOT NULL" +
                ");");
        db.execSQL("CREATE UNIQUE INDEX idx_forward ON forward(protocol, dport)");
    }

    private boolean columnExists(SQLiteDatabase db, String table, String column) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT * FROM " + table + " LIMIT 0", null);
            return (cursor.getColumnIndex(column) >= 0);
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            return false;
        } finally {
            if (cursor != null)
                cursor.close();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, DB_NAME + " upgrading from version " + oldVersion + " to " + newVersion);

        db.beginTransaction();
        try {
            if (oldVersion < 2) {
                if (!columnExists(db, "log", "version"))
                    db.execSQL("ALTER TABLE log ADD COLUMN version INTEGER NULL");
                if (!columnExists(db, "log", "protocol"))
                    db.execSQL("ALTER TABLE log ADD COLUMN protocol INTEGER NULL");
                if (!columnExists(db, "log", "uid"))
                    db.execSQL("ALTER TABLE log ADD COLUMN uid INTEGER NULL");
                oldVersion = 2;
            }
            if (oldVersion < 3) {
                if (!columnExists(db, "log", "port"))
                    db.execSQL("ALTER TABLE log ADD COLUMN port INTEGER NULL");
                if (!columnExists(db, "log", "flags"))
                    db.execSQL("ALTER TABLE log ADD COLUMN flags TEXT");
                oldVersion = 3;
            }
            if (oldVersion < 4) {
                if (!columnExists(db, "log", "connection"))
                    db.execSQL("ALTER TABLE log ADD COLUMN connection INTEGER NULL");
                oldVersion = 4;
            }
            if (oldVersion < 5) {
                if (!columnExists(db, "log", "interactive"))
                    db.execSQL("ALTER TABLE log ADD COLUMN interactive INTEGER NULL");
                oldVersion = 5;
            }
            if (oldVersion < 6) {
                if (!columnExists(db, "log", "allowed"))
                    db.execSQL("ALTER TABLE log ADD COLUMN allowed INTEGER NULL");
                oldVersion = 6;
            }
            if (oldVersion < 7) {
                db.execSQL("DROP TABLE log");
                createTableLog(db);
                oldVersion = 8;
            }
            if (oldVersion < 8) {
                if (!columnExists(db, "log", "data"))
                    db.execSQL("ALTER TABLE log ADD COLUMN data TEXT");
                db.execSQL("DROP INDEX idx_log_source");
                db.execSQL("DROP INDEX idx_log_dest");
                db.execSQL("CREATE INDEX idx_log_source ON log(saddr)");
                db.execSQL("CREATE INDEX idx_log_dest ON log(daddr)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_uid ON log(uid)");
                oldVersion = 8;
            }
            if (oldVersion < 9) {
                createTableAccess(db);
                oldVersion = 9;
            }
            if (oldVersion < 10) {
                db.execSQL("DROP TABLE log");
                db.execSQL("DROP TABLE access");
                createTableLog(db);
                createTableAccess(db);
                oldVersion = 10;
            }
            if (oldVersion < 12) {
                db.execSQL("DROP TABLE access");
                createTableAccess(db);
                oldVersion = 12;
            }
            if (oldVersion < 13) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_dport ON log(dport)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_dname ON log(dname)");
                oldVersion = 13;
            }
            if (oldVersion < 14) {
                createTableDns(db);
                oldVersion = 14;
            }
            if (oldVersion < 15) {
                db.execSQL("DROP TABLE access");
                createTableAccess(db);
                oldVersion = 15;
            }
            if (oldVersion < 16) {
                createTableForward(db);
                oldVersion = 16;
            }
            if (oldVersion < 17) {
                if (!columnExists(db, "access", "sent"))
                    db.execSQL("ALTER TABLE access ADD COLUMN sent INTEGER NULL");
                if (!columnExists(db, "access", "received"))
                    db.execSQL("ALTER TABLE access ADD COLUMN received INTEGER NULL");
                oldVersion = 17;
            }
            if (oldVersion < 18) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_access_block ON access(block)");
                db.execSQL("DROP INDEX idx_dns");
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_dns ON dns(qname, aname, resource)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_dns_resource ON dns(resource)");
                oldVersion = 18;
            }
            if (oldVersion < 19) {
                if (!columnExists(db, "access", "connections"))
                    db.execSQL("ALTER TABLE access ADD COLUMN connections INTEGER NULL");
                oldVersion = 19;
            }
            if (oldVersion < 20) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_access_daddr ON access(daddr)");
                oldVersion = 20;
            }
            if(oldVersion < 22) {
                if (!columnExists(db, "log", "packageName"))
                    db.execSQL("ALTER TABLE log ADD COLUMN packageName TEXT NULL");
                 oldVersion = 22;
            }


            if (oldVersion == DB_VERSION) {
                db.setVersion(oldVersion);
                db.setTransactionSuccessful();
                Log.i(TAG, DB_NAME + " upgraded to " + DB_VERSION);
            } else
                throw new IllegalArgumentException(DB_NAME + " upgraded to " + oldVersion + " but required " + DB_VERSION);

        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        } finally {
            db.endTransaction();
        }
    }

    // Log

    public void insertLog(Packet packet, String dname, String packageName, int connection, boolean interactive) {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                ContentValues cv = new ContentValues();
                cv.put("time", packet.time);
                cv.put("version", packet.version);

                if (packet.protocol < 0)
                    cv.putNull("protocol");
                else
                    cv.put("protocol", packet.protocol);

                cv.put("flags", packet.flags);

                cv.put("saddr", packet.saddr);
                if (packet.sport < 0)
                    cv.putNull("sport");
                else
                    cv.put("sport", packet.sport);

                cv.put("daddr", packet.daddr);
                if (packet.dport < 0)
                    cv.putNull("dport");
                else
                    cv.put("dport", packet.dport);

                if (dname == null)
                    cv.putNull("dname");
                else
                    cv.put("dname", dname);

                cv.put("packageName",packageName);

                cv.put("data", packet.data);

                if (packet.uid < 0)
                    cv.putNull("uid");
                else
                    cv.put("uid", packet.uid);

                cv.put("allowed", packet.allowed ? 1 : 0);

                cv.put("connection", connection);
                cv.put("interactive", interactive ? 1 : 0);

                if (db.insert("log", null, cv) == -1)
                    Log.e(TAG, "Insert log failed");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }

        notifyLogChanged();
    }

    public void clearLog() {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.delete("log", null, new String[]{});

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            db.execSQL("VACUUM");
        } finally {
            mLock.writeLock().unlock();
        }

        notifyLogChanged();
    }

    public void cleanupLog(long time) {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                // There an index on time
                int rows = db.delete("log", "time < ?", new String[]{Long.toString(time)});
                Log.i(TAG, "Cleanup log" +
                        " before=" + SimpleDateFormat.getDateTimeInstance().format(new Date(time)) +
                        " rows=" + rows);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }
    }

    public Cursor getLog(boolean udp, boolean tcp, boolean other, boolean allowed, boolean blocked) {
        mLock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is an index on time
            // There is no index on protocol/allowed for write performance
            String query = "SELECT ID AS _id, *";
            query += " FROM log";
            query += " WHERE (0 = 1";
            if (udp)
                query += " OR protocol = 17";
            if (tcp)
                query += " OR protocol = 6";
            if (other)
                query += " OR (protocol <> 6 AND protocol <> 17)";
            query += ") AND (0 = 1";
            if (allowed)
                query += " OR allowed = 1";
            if (blocked)
                query += " OR allowed = 0";
            query += ")";
            query += " ORDER BY time DESC";
            return db.rawQuery(query, new String[]{});
        } finally {
            mLock.readLock().unlock();
        }
    }

    public Cursor searchLog(String find) {
        mLock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is an index on daddr, dname, dport and uid
            String query = "SELECT ID AS _id, *";
            query += " FROM log";
            query += " WHERE daddr LIKE ? OR dname LIKE ? OR dport = ? OR uid = ?";
            query += " ORDER BY time DESC";
            return db.rawQuery(query, new String[]{"%" + find + "%", "%" + find + "%", find, find});
        } finally {
            mLock.readLock().unlock();
        }
    }

    // Access

    public boolean updateAccess(Packet packet, String dname, int block) {
        int rows;

        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                ContentValues cv = new ContentValues();
                cv.put("time", packet.time);
                cv.put("allowed", packet.allowed ? 1 : 0);
                if (block >= 0)
                    cv.put("block", block);

                // There is a segmented index on uid, version, protocol, daddr and dport
                rows = db.update("access", cv, "uid = ? AND version = ? AND protocol = ? AND daddr = ? AND dport = ?",
                        new String[]{
                                Integer.toString(packet.uid),
                                Integer.toString(packet.version),
                                Integer.toString(packet.protocol),
                                dname == null ? packet.daddr : dname,
                                Integer.toString(packet.dport)});

                if (rows == 0) {
                    cv.put("uid", packet.uid);
                    cv.put("version", packet.version);
                    cv.put("protocol", packet.protocol);
                    cv.put("daddr", dname == null ? packet.daddr : dname);
                    cv.put("dport", packet.dport);
                    if (block < 0)
                        cv.put("block", block);

                    if (db.insert("access", null, cv) == -1)
                        Log.e(TAG, "Insert access failed");
                } else if (rows != 1)
                    Log.e(TAG, "Update access failed rows=" + rows);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }

        notifyAccessChanged();
        return (rows == 0);
    }

    public void updateUsage(Usage usage, String dname) {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                // There is a segmented index on uid, version, protocol, daddr and dport
                String selection = "uid = ? AND version = ? AND protocol = ? AND daddr = ? AND dport = ?";
                String[] selectionArgs = new String[]{
                        Integer.toString(usage.Uid),
                        Integer.toString(usage.Version),
                        Integer.toString(usage.Protocol),
                        dname == null ? usage.DAddr : dname,
                        Integer.toString(usage.DPort)
                };

                Cursor cursor = db.query("access", new String[]{"sent", "received", "connections"}, selection, selectionArgs, null, null, null);
                long sent = 0;
                long received = 0;
                int connections = 0;
                int colSent = cursor.getColumnIndex("sent");
                int colReceived = cursor.getColumnIndex("received");
                int colConnections = cursor.getColumnIndex("connections");
                if (cursor.moveToNext()) {
                    sent = cursor.isNull(colSent) ? 0 : cursor.getLong(colSent);
                    received = cursor.isNull(colReceived) ? 0 : cursor.getLong(colReceived);
                    connections = cursor.isNull(colConnections) ? 0 : cursor.getInt(colConnections);
                }
                cursor.close();

                ContentValues cv = new ContentValues();
                cv.put("sent", sent + usage.Sent);
                cv.put("received", received + usage.Received);
                cv.put("connections", connections + 1);

                int rows = db.update("access", cv, selection, selectionArgs);
                if (rows != 1)
                    Log.e(TAG, "Update usage failed rows=" + rows);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public void setAccess(long id, int block) {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                ContentValues cv = new ContentValues();
                cv.put("block", block);
                cv.put("allowed", -1);

                if (db.update("access", cv, "ID = ?", new String[]{Long.toString(id)}) != 1)
                    Log.e(TAG, "Set access failed");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public void clearAccess() {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.delete("access", null, null);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public void clearAccess(int uid, boolean keeprules) {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                // There is a segmented index on uid
                // There is an index on block
                if (keeprules)
                    db.delete("access", "uid = ? AND block < 0", new String[]{Integer.toString(uid)});
                else
                    db.delete("access", "uid = ?", new String[]{Integer.toString(uid)});

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public void resetUsage(int uid) {
        mLock.writeLock().lock();
        try {
            // There is a segmented index on uid
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                ContentValues cv = new ContentValues();
                cv.putNull("sent");
                cv.putNull("received");
                cv.putNull("connections");
                db.update("access", cv,
                        (uid < 0 ? null : "uid = ?"),
                        (uid < 0 ? null : new String[]{Integer.toString(uid)}));

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public Cursor getAccess(int uid) {
        mLock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on uid
            // There is no index on time for write performance
            String query = "SELECT ID AS _id, *";
            query += " FROM access";
            query += " WHERE uid = ?";
            query += " ORDER BY time DESC";
            query += " LIMIT 50";
            return db.rawQuery(query, new String[]{Integer.toString(uid)});
        } finally {
            mLock.readLock().unlock();
        }
    }

    public Cursor getAccess() {
        mLock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on uid
            // There is an index on block
            return db.query("access", null, "block >= 0", null, null, null, "uid");
        } finally {
            mLock.readLock().unlock();
        }
    }

    public Cursor getAccessUnset(int uid, int limit) {
        mLock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on uid, block and daddr
            // There is no index on allowed and time for write performance
            String query = "SELECT MAX(time) AS time, daddr, allowed";
            query += " FROM access";
            query += " WHERE uid = ?";
            query += " AND block < 0";
            query += " GROUP BY daddr, allowed";
            query += " ORDER BY time DESC";
            if (limit > 0)
                query += " LIMIT " + limit;
            return db.rawQuery(query, new String[]{Integer.toString(uid)});
        } finally {
            mLock.readLock().unlock();
        }
    }

    public long getRuleCount(int uid) {
        mLock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on uid
            // There is an index on block
            return db.compileStatement("SELECT COUNT(*) FROM access WHERE block >= 0 AND uid =" + uid).simpleQueryForLong();
        } finally {
            mLock.readLock().unlock();
        }
    }

    // DNS

    public boolean insertDns(ResourceRecord rr) {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                int ttl = rr.TTL;
                if (ttl < 15 * 60)
                    ttl = 15 * 60;

                ContentValues cv = new ContentValues();
                cv.put("time", rr.Time);
                cv.put("ttl", ttl * 1000L);

                int rows = db.update("dns", cv, "qname = ? AND aname = ? AND resource = ?",
                        new String[]{rr.QName, rr.AName, rr.Resource});

                if (rows == 0) {
                    cv.put("qname", rr.QName);
                    cv.put("aname", rr.AName);
                    cv.put("resource", rr.Resource);

                    if (db.insert("dns", null, cv) == -1)
                        Log.e(TAG, "Insert dns failed");
                } else if (rows != 1)
                    Log.e(TAG, "Update dns failed rows=" + rows);

                db.setTransactionSuccessful();

                return (rows == 0);
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }
    }

    public void cleanupDns() {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                // There is no index on time for write performance
                long now = new Date().getTime();
                db.execSQL("DELETE FROM dns WHERE time + ttl < " + now);
                Log.i(TAG, "Cleanup DNS");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }
    }

    public void clearDns() {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.delete("dns", null, new String[]{});

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }
    }

    public String getQName(String ip) {
        mLock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on resource
            String query = "SELECT qname";
            query += " FROM dns";
            query += " WHERE resource = '" + ip.replace("'", "''") + "'";
            query += " ORDER BY time DESC";
            query += " LIMIT 1";
            return db.compileStatement(query).simpleQueryForString();
        } catch (SQLiteDoneException ignored) {
            // Not found
            return null;
        } finally {
            mLock.readLock().unlock();
        }
    }

    public Cursor getDns() {
        mLock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on qname
            String query = "SELECT ID AS _id, *";
            query += " FROM dns";
            query += " ORDER BY qname, resource";
            return db.rawQuery(query, new String[]{});
        } finally {
            mLock.readLock().unlock();
        }
    }

    public Cursor getAccessDns(String dname) {
        long now = new Date().getTime();
        mLock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();

            // There is a segmented index on dns.qname
            // There is an index on access.daddr and access.block
            String query = "SELECT a.uid, a.version, a.protocol, a.daddr, d.resource, a.dport, a.block, d.time, d.ttl";
            query += " FROM access AS a";
            query += " LEFT JOIN dns AS d";
            query += "   ON d.qname = a.daddr";
            query += " WHERE a.block >= 0";
            query += " AND d.time + d.ttl >= " + now;
            if (dname != null)
                query += " AND a.daddr = ?";

            return db.rawQuery(query, dname == null ? new String[]{} : new String[]{dname});
        } finally {
            mLock.readLock().unlock();
        }
    }

    // Forward

    public void addForward(int protocol, int dport, String raddr, int rport, int ruid) {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                ContentValues cv = new ContentValues();
                cv.put("protocol", protocol);
                cv.put("dport", dport);
                cv.put("raddr", raddr);
                cv.put("rport", rport);
                cv.put("ruid", ruid);

                if (db.insert("forward", null, cv) < 0)
                    Log.e(TAG, "Insert forward failed");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }

        notifyForwardChanged();
    }

    public void deleteForward() {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.delete("forward", null, null);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }

        notifyForwardChanged();
    }

    public void deleteForward(int protocol, int dport) {
        mLock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.delete("forward", "protocol = ? AND dport = ?",
                        new String[]{Integer.toString(protocol), Integer.toString(dport)});

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            mLock.writeLock().unlock();
        }

        notifyForwardChanged();
    }

    public Cursor getForwarding() {
        mLock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            String query = "SELECT ID AS _id, *";
            query += " FROM forward";
            query += " ORDER BY dport";
            return db.rawQuery(query, new String[]{});
        } finally {
            mLock.readLock().unlock();
        }
    }

    public void addLogChangedListener(LogChangedListener listener) {
        logChangedListeners.add(listener);
    }

    public void removeLogChangedListener(LogChangedListener listener) {
        logChangedListeners.remove(listener);
    }

    public void addAccessChangedListener(AccessChangedListener listener) {
        accessChangedListeners.add(listener);
    }

    public void removeAccessChangedListener(AccessChangedListener listener) {
        accessChangedListeners.remove(listener);
    }

    public void addForwardChangedListener(ForwardChangedListener listener) {
        forwardChangedListeners.add(listener);
    }

    public void removeForwardChangedListener(ForwardChangedListener listener) {
        forwardChangedListeners.remove(listener);
    }

    private void notifyLogChanged() {
        Message msg = handler.obtainMessage();
        msg.what = MSG_LOG;
        handler.sendMessage(msg);
    }

    private void notifyAccessChanged() {
        Message msg = handler.obtainMessage();
        msg.what = MSG_ACCESS;
        handler.sendMessage(msg);
    }

    private void notifyForwardChanged() {
        Message msg = handler.obtainMessage();
        msg.what = MSG_FORWARD;
        handler.sendMessage(msg);
    }

    private static void handleChangedNotification(Message msg) {
        // Batch notifications
        try {
            Thread.sleep(1000);
            if (handler.hasMessages(msg.what))
                handler.removeMessages(msg.what);
        } catch (InterruptedException ignored) {
        }

        // Notify listeners
        if (msg.what == MSG_LOG) {
            for (LogChangedListener listener : logChangedListeners)
                try {
                    listener.onChanged();
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }

        } else if (msg.what == MSG_ACCESS) {
            for (AccessChangedListener listener : accessChangedListeners)
                try {
                    listener.onChanged();
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }

        } else if (msg.what == MSG_FORWARD) {
            for (ForwardChangedListener listener : forwardChangedListeners)
                try {
                    listener.onChanged();
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }
        }
    }

    public interface LogChangedListener {
        void onChanged();
    }

    public interface AccessChangedListener {
        void onChanged();
    }

    public interface ForwardChangedListener {
        void onChanged();
    }

    public boolean exportLog( Context context) {

        String filename = "netguard_log_" + new SimpleDateFormat("yyyyMMdd").format(new Date().getTime()) + ".csv";

         List<String> allRecords;

        allRecords = new ArrayList<>();

         Log.i(TAG,"Exporting log");

        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            return false;
        }
        else {
            //We use the Download directory for saving our .csv file.
            File exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }

            File file;


            PrintStream printStream = null;
            try {

                file = new File(exportDir, filename);
                file.createNewFile();
                printStream = new PrintStream(new FileOutputStream(file));

                /**This is our database connector class that reads the data from the database.
                 * The code of this class is omitted for brevity.
                 */
                SQLiteDatabase db = this.getReadableDatabase(); //open the database for reading

                /**Let's read the first table of the database.
                 * getFirstTable() is a method in our DBCOurDatabaseConnector class which retrieves a Cursor
                 * containing all records of the table (all fields).
                 * The code of this class is omitted for brevity.
                 */

                Cursor cursor = getLog(true, true, true, true, true);

                //Write the name of the table and the name of the columns (comma separated values) in the .csv file.

                printStream.println("TIMESTAMP,PACKAGE_NAME,UID,VERSION,PROTOCOL,SADDR,SPORT,DADDR, DPORT,DNAME,APP_NAME,DATA");
                while (cursor.moveToNext()) {


                    long time = cursor.getLong(cursor.getColumnIndex("time"));
                    final String packagename = cursor.getString(cursor.getColumnIndex("packageName"));
                    final int uid = (cursor.isNull(cursor.getColumnIndex("uid")) ? -1 : cursor.getInt(cursor.getColumnIndex("uid")));
                    int version = (cursor.getInt(cursor.getColumnIndex("version")));
                    int protocol = cursor.getInt(cursor.getColumnIndex("protocol"));
                    final String saddr = cursor.getString(cursor.getColumnIndex("saddr"));
                    final int sport = (cursor.isNull(cursor.getColumnIndex("sport")) ? -1 : cursor.getInt(cursor.getColumnIndex("sport")));
                    final String daddr = cursor.getString(cursor.getColumnIndex("daddr"));
                    final int dport = (cursor.isNull(cursor.getColumnIndex("dport")) ? -1 : cursor.getInt(cursor.getColumnIndex("dport")));
                   String dname = cursor.getString(cursor.getColumnIndex("dname"));

                    if(dname == null){

                        dname = "unknown";
                    }
                    final String data = cursor.getString(cursor.getColumnIndex("data"));


                        if(packagename != null) {

                            //get the label
                            List<String> appnames = getApplicationNames(uid, context);

                            /**Create the line to write in the .csv file.
                             * We need a String where values are comma separated.
                             * The field date (Long) is formatted in a readable text. The amount field
                             * is converted into String.
                             */
                            String record = "" + time + "," + packagename + "," + uid + "," + version + "," + protocol + "," + saddr + "," + sport + "," + daddr +
                                    "," + dport + "," + dname + "," + appnames.get(0) + "," + data;
                            printStream.println(record); //write the record in the .csv file

                            String extRecord = "" + packagename + "," + version + "," + protocol + "," + daddr +
                                    "," + dport + "," + dname + "," + appnames.get(0) + "," + data;

                            allRecords.add(new String(extRecord));
                        }

                }



                cursor.close();

                List<String> noDups = new ArrayList<String>(new LinkedHashSet<String>(allRecords));

                Collections.sort(noDups);
                String exportFilename = "netguard_export_log_" + new SimpleDateFormat("yyyyMMdd").format(new Date().getTime()) + ".csv";
                File file1 = new File(exportDir, exportFilename);
                file1.createNewFile();
                PrintStream printStream1 = new PrintStream(new FileOutputStream(file1));
                printStream1.println("PACKAGE_NAME,VERSION,PROTOCOL,DADDR, DPORT,DNAME,APP_NAME,DATA");
                for(int x = 0; x<noDups.size();x++){

                    printStream1.println(noDups.get(x));
                }

                printStream1.close();
            } catch (Exception exc) {
                Log.e(TAG, exc.toString() + "\n" + Log.getStackTraceString(exc));
                return false;
            } finally {
                if (printStream != null) printStream.close();
            }

            //If there are no errors, return true.
            return true;
        }

    }


    public void generateHtmlAppReports(String exportFile){

        //open the export file and for each unique app generate a pretty
        //report that shows network activity, icon, and permissions



    }


}

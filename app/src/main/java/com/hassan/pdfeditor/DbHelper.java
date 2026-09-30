package com.hassan.pdfeditor;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class DbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "balady_manager.db";
    private static final int DB_VERSION = 1;

    public static class Permit {
        public long id;
        public String permitNumber = "";
        public String permitType = "";
        public String company = "";
        public String issueDate = "";
        public String expiryDate = "";
        public String status = "";
        public String municipality = "";
        public String pdfUrl = "";
        public String rawJson = "";
        public long syncedAt;
    }

    public DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE accounts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "created_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE permits (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "permit_number TEXT NOT NULL UNIQUE," +
                "permit_type TEXT," +
                "company TEXT," +
                "issue_date TEXT," +
                "expiry_date TEXT," +
                "status TEXT," +
                "municipality TEXT," +
                "pdf_url TEXT," +
                "raw_json TEXT," +
                "synced_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE audit_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "event_type TEXT NOT NULL," +
                "message TEXT," +
                "created_at INTEGER NOT NULL)");

        ContentValues cv = new ContentValues();
        cv.put("name", "حساب بلدي");
        cv.put("created_at", System.currentTimeMillis());
        db.insert("accounts", null, cv);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public void upsertPermit(Permit p) {
        ContentValues cv = new ContentValues();
        cv.put("permit_number", p.permitNumber);
        cv.put("permit_type", p.permitType);
        cv.put("company", p.company);
        cv.put("issue_date", p.issueDate);
        cv.put("expiry_date", p.expiryDate);
        cv.put("status", p.status);
        cv.put("municipality", p.municipality);
        cv.put("pdf_url", p.pdfUrl);
        cv.put("raw_json", p.rawJson);
        cv.put("synced_at", p.syncedAt);
        getWritableDatabase().insertWithOnConflict(
                "permits", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<Permit> listPermits(String search, String statusFilter) {
        List<Permit> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id,permit_number,permit_type,company,issue_date,expiry_date,status,municipality,pdf_url,raw_json,synced_at FROM permits WHERE 1=1");
        List<String> args = new ArrayList<>();

        if (search != null && !search.trim().isEmpty()) {
            sql.append(" AND (permit_number LIKE ? OR permit_type LIKE ? OR company LIKE ? OR municipality LIKE ?)");
            String q = "%" + search.trim() + "%";
            args.add(q); args.add(q); args.add(q); args.add(q);
        }
        if (statusFilter != null && !statusFilter.isEmpty() && !"الكل".equals(statusFilter)) {
            sql.append(" AND status LIKE ?");
            args.add("%" + statusFilter + "%");
        }
        sql.append(" ORDER BY synced_at DESC");

        try (Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                Permit p = new Permit();
                p.id = c.getLong(0);
                p.permitNumber = safe(c.getString(1));
                p.permitType = safe(c.getString(2));
                p.company = safe(c.getString(3));
                p.issueDate = safe(c.getString(4));
                p.expiryDate = safe(c.getString(5));
                p.status = safe(c.getString(6));
                p.municipality = safe(c.getString(7));
                p.pdfUrl = safe(c.getString(8));
                p.rawJson = safe(c.getString(9));
                p.syncedAt = c.getLong(10);
                out.add(p);
            }
        }
        return out;
    }

    public void audit(String type, String message) {
        ContentValues cv = new ContentValues();
        cv.put("event_type", type);
        cv.put("message", message);
        cv.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("audit_log", null, cv);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}

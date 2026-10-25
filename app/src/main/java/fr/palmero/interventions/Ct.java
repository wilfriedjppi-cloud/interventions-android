package fr.palmero.interventions;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/** Contacts du téléphone (synchronisés par Android avec Google Contacts / Gmail). */
class Ct {
    static String googleAccount(ContentResolver cr) {
        try (Cursor c = cr.query(RawContacts.CONTENT_URI, new String[]{RawContacts.ACCOUNT_NAME}, RawContacts.ACCOUNT_TYPE + "=?", new String[]{"com.google"}, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    static JSONObject contact(ContentResolver cr, long id) throws Exception {
        JSONObject o = new JSONObject(); JSONArray ph = new JSONArray(), em = new JSONArray();
        o.put("id", id);
        try (Cursor c = cr.query(Data.CONTENT_URI, new String[]{Data.MIMETYPE, Data.DATA1, Data.DATA4, Data.DISPLAY_NAME}, Data.CONTACT_ID + "=?", new String[]{String.valueOf(id)}, null)) {
            while (c != null && c.moveToNext()) {
                String mt = c.getString(0), d1 = c.getString(1);
                if (!o.has("name") && c.getString(3) != null) o.put("name", c.getString(3));
                if (d1 == null) continue;
                if (Phone.CONTENT_ITEM_TYPE.equals(mt)) ph.put(d1);
                else if (Email.CONTENT_ITEM_TYPE.equals(mt)) em.put(d1);
                else if (Organization.CONTENT_ITEM_TYPE.equals(mt)) { o.put("org", d1); if (c.getString(2) != null) o.put("title", c.getString(2)); }
            }
        }
        o.put("phones", ph); o.put("emails", em);
        return o;
    }

    /** q : texte cherché (nom, société, mail) ; org=true : société seulement. */
    static String find(ContentResolver cr, String q, boolean org) {
        JSONArray out = new JSONArray();
        try {
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            String like = "%" + q + "%", sel; String[] args;
            if (org) { sel = Data.MIMETYPE + "=? AND " + Organization.COMPANY + " LIKE ?"; args = new String[]{Organization.CONTENT_ITEM_TYPE, like}; }
            else { sel = Data.DISPLAY_NAME + " LIKE ? OR (" + Data.MIMETYPE + "=? AND " + Email.ADDRESS + " LIKE ?) OR (" + Data.MIMETYPE + "=? AND " + Organization.COMPANY + " LIKE ?)"; args = new String[]{like, Email.CONTENT_ITEM_TYPE, like, Organization.CONTENT_ITEM_TYPE, like}; }
            try (Cursor c = cr.query(Data.CONTENT_URI, new String[]{Data.CONTACT_ID}, sel, args, null)) {
                while (c != null && c.moveToNext() && ids.size() < 60) ids.add(c.getLong(0));
            }
            for (long id : ids) out.put(contact(cr, id));
        } catch (Exception e) { return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
        return out.toString();
    }

    static long rawOf(ContentResolver cr, String email, String phone) {
        if (email != null && !email.isEmpty()) try (Cursor c = cr.query(Data.CONTENT_URI, new String[]{Data.RAW_CONTACT_ID}, Data.MIMETYPE + "=? AND " + Email.ADDRESS + "=? COLLATE NOCASE", new String[]{Email.CONTENT_ITEM_TYPE, email}, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) { }
        if (phone != null && phone.replaceAll("\\D", "").length() >= 8) try (Cursor c = cr.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone)), new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                long cid = c.getLong(0);
                try (Cursor r = cr.query(RawContacts.CONTENT_URI, new String[]{RawContacts._ID}, RawContacts.CONTACT_ID + "=?", new String[]{String.valueOf(cid)}, null)) { if (r != null && r.moveToFirst()) return r.getLong(0); }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    static boolean has(ContentResolver cr, long raw, String mt, String col, String v) {
        try (Cursor c = cr.query(Data.CONTENT_URI, new String[]{Data._ID}, Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "=? AND " + col + "=? COLLATE NOCASE", new String[]{String.valueOf(raw), mt, v}, null)) { return c != null && c.moveToFirst(); }
        catch (Exception e) { return true; }
    }

    static void add(ContentResolver cr, long raw, String mt, String[] kv) {
        ContentValues v = new ContentValues(); v.put(Data.RAW_CONTACT_ID, raw); v.put(Data.MIMETYPE, mt);
        for (int i = 0; i + 1 < kv.length; i += 2) v.put(kv[i], kv[i + 1]);
        cr.insert(Data.CONTENT_URI, v);
    }

    /** Crée ou complète un contact ; {name, org, title, phone, email} -> {res: created|updated|same, account} */
    static String save(ContentResolver cr, String json) {
        try {
            JSONObject o = new JSONObject(json);
            String name = o.optString("name", "").trim(), org = o.optString("org", "").trim(), title = o.optString("title", "").trim(), phone = o.optString("phone", "").trim(), email = o.optString("email", "").trim();
            if (name.isEmpty() && email.isEmpty() && phone.isEmpty()) return "{\"res\":\"vide\"}";
            long raw = rawOf(cr, email, phone);
            String acc = googleAccount(cr);
            if (raw >= 0) {
                boolean ch = false;
                if (!email.isEmpty() && !has(cr, raw, Email.CONTENT_ITEM_TYPE, Email.ADDRESS, email)) { add(cr, raw, Email.CONTENT_ITEM_TYPE, new String[]{Email.ADDRESS, email, Email.TYPE, String.valueOf(Email.TYPE_WORK)}); ch = true; }
                if (!phone.isEmpty() && !has(cr, raw, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, phone)) { add(cr, raw, Phone.CONTENT_ITEM_TYPE, new String[]{Phone.NUMBER, phone, Phone.TYPE, String.valueOf(Phone.TYPE_WORK)}); ch = true; }
                boolean hasOrg;
                try (Cursor c = cr.query(Data.CONTENT_URI, new String[]{Data._ID}, Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?", new String[]{String.valueOf(raw), Organization.CONTENT_ITEM_TYPE}, null)) { hasOrg = c != null && c.moveToFirst(); }
                if (!org.isEmpty() && !hasOrg) { add(cr, raw, Organization.CONTENT_ITEM_TYPE, new String[]{Organization.COMPANY, org, Organization.TITLE, title, Organization.TYPE, String.valueOf(Organization.TYPE_WORK)}); ch = true; }
                return "{\"res\":\"" + (ch ? "updated" : "same") + "\"}";
            }
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI).withValue(RawContacts.ACCOUNT_TYPE, acc == null ? null : "com.google").withValue(RawContacts.ACCOUNT_NAME, acc).build());
            if (!name.isEmpty()) ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0).withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build());
            if (!org.isEmpty()) ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0).withValue(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE).withValue(Organization.COMPANY, org).withValue(Organization.TITLE, title).withValue(Organization.TYPE, Organization.TYPE_WORK).build());
            if (!phone.isEmpty()) ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0).withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, phone).withValue(Phone.TYPE, Phone.TYPE_WORK).build());
            if (!email.isEmpty()) ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0).withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, email).withValue(Email.TYPE, Email.TYPE_WORK).build());
            cr.applyBatch(ContactsContract.AUTHORITY, ops);
            return "{\"res\":\"created\",\"account\":" + (acc == null ? "null" : JSONObject.quote(acc)) + "}";
        } catch (Exception e) { return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
    }
}

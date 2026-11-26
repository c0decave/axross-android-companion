package io.github.c0decave.axross.companion;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import io.github.c0decave.axross.companion.protocol.ErrorCode;
import io.github.c0decave.axross.companion.protocol.Json;
import io.github.c0decave.axross.companion.protocol.RequestException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The phone's contacts (read only), in the protocol's objects. */
final class Contacts {
    static final int MAX_PHOTO = 8 * 1024 * 1024;

    private final Context context;
    private final ContentResolver cr;

    Contacts(Context context) {
        this.context = context;
        this.cr = context.getContentResolver();
    }

    /** A contact found for an address. */
    static final class Match {
        final long id;
        final String name;

        Match(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    Map<String, Object> list(int offset, int limit) throws RequestException {
        Perms.require(context, "READ_CONTACTS");
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        int total;
        Cursor c = query(ContactsContract.Contacts.CONTENT_URI, new String[] {
                ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.PHOTO_ID}, null, null, ContactsContract.Contacts.SORT_KEY_PRIMARY + " ASC");
        try {
            total = c.getCount();
            if (offset < total && c.moveToPosition(offset)) {
                do {
                    long id = c.getLong(0);
                    byId.put(id, Json.obj("id", id, "lookup_key", c.getString(1), "name", c.getString(2),
                            "starred", c.getInt(3) != 0, "has_photo", !c.isNull(4) && c.getLong(4) != 0,
                            "numbers", new ArrayList<Object>()));
                } while (byId.size() < limit && c.moveToNext());
            }
        } finally {
            c.close();
        }
        if (!byId.isEmpty()) {
            // (the ids are the provider's own numbers, not input: written into the clause)
            StringBuilder in = new StringBuilder(ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " IN (");
            boolean first = true;
            for (Long id : byId.keySet()) {
                if (!first) {
                    in.append(',');
                }
                first = false;
                in.append(id.longValue());
            }
            in.append(')');
            Cursor p = query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, new String[] {
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL}, in.toString(), null,
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC");
            try {
                while (p.moveToNext()) {
                    Map<String, Object> contact = byId.get(p.getLong(0));
                    String number = p.getString(1);
                    if (contact == null || number == null) {
                        continue;
                    }
                    int type = p.isNull(3) ? 0 : p.getInt(3);
                    @SuppressWarnings("unchecked")
                    List<Object> numbers = (List<Object>) contact.get("numbers");
                    numbers.add(Json.obj("number", number, "normalized", p.getString(2), "type", typeName(type),
                            "label", type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM ? p.getString(4) : null));
                }
            } finally {
                p.close();
            }
        }
        boolean more = total > offset + limit;
        return Json.obj("contacts", new ArrayList<Object>(byId.values()), "total", (long) total,
                "next_offset", more ? (Object) (long) (offset + limit) : Json.NULL);
    }

    static String typeName(int type) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM: return "custom";
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME: return "home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE: return "mobile";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK: return "work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK: return "fax_work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME: return "fax_home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_PAGER: return "pager";
            case ContactsContract.CommonDataKinds.Phone.TYPE_OTHER: return "other";
            case ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK: return "callback";
            case ContactsContract.CommonDataKinds.Phone.TYPE_CAR: return "car";
            case ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN: return "company_main";
            case ContactsContract.CommonDataKinds.Phone.TYPE_ISDN: return "isdn";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MAIN: return "main";
            case ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX: return "other_fax";
            case ContactsContract.CommonDataKinds.Phone.TYPE_RADIO: return "radio";
            case ContactsContract.CommonDataKinds.Phone.TYPE_TELEX: return "telex";
            case ContactsContract.CommonDataKinds.Phone.TYPE_TTY_TDD: return "tty_tdd";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE: return "work_mobile";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER: return "work_pager";
            case ContactsContract.CommonDataKinds.Phone.TYPE_ASSISTANT: return "assistant";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MMS: return "mms";
            default: return "other";
        }
    }

    /** contacts.photo: the bytes and their type. */
    SmsStore.PartData photo(long contactId, boolean full) throws RequestException {
        Perms.require(context, "READ_CONTACTS");
        Uri uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId);
        try (InputStream in = ContactsContract.Contacts.openContactPhotoInputStream(cr, uri, full)) {
            if (in == null) {
                throw new RequestException(ErrorCode.NOT_FOUND, "no photo");
            }
            byte[] data = SmsStore.readAtMost(in, MAX_PHOTO);
            return new SmsStore.PartData(data, sniff(data));
        } catch (IOException e) {
            throw new RequestException(ErrorCode.NOT_FOUND, "no photo");
        } catch (SecurityException e) {
            throw RequestException.permission("READ_CONTACTS");
        }
    }

    static String sniff(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8) {
            return "image/jpeg";
        }
        if (d.length >= 4 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E') {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    /** The contact for a phone number, or null (also without READ_CONTACTS). */
    Match lookup(String address) {
        if (address == null || address.isEmpty() || !Perms.has(context, "READ_CONTACTS")) {
            return null;
        }
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
        try {
            Cursor c = cr.query(uri, new String[] {ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null);
            if (c == null) {
                return null;
            }
            try {
                return c.moveToFirst() ? new Match(c.getLong(0), c.getString(1)) : null;
            } finally {
                c.close();
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) throws RequestException {
        try {
            Cursor c = cr.query(uri, projection, sel, args, sort);
            if (c == null) {
                throw new RequestException(ErrorCode.INTERNAL, "the contacts did not answer");
            }
            return c;
        } catch (SecurityException e) {
            throw RequestException.permission("READ_CONTACTS");
        }
    }
}

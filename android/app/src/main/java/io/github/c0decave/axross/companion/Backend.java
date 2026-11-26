package io.github.c0decave.axross.companion;

import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import io.github.c0decave.axross.companion.protocol.ErrorCode;
import io.github.c0decave.axross.companion.protocol.Json;
import io.github.c0decave.axross.companion.protocol.MessageOrder;
import io.github.c0decave.axross.companion.protocol.Protocol;
import io.github.c0decave.axross.companion.protocol.RequestException;
import io.github.c0decave.axross.companion.protocol.Requests;
import io.github.c0decave.axross.companion.protocol.SendTracker;
import io.github.c0decave.axross.companion.protocol.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The phone's side of the protocol: what each request does once
 * {@link Requests#parse} has checked it (docs/PROTOCOL.md, section 7).
 */
final class Backend implements Session.Handler {
    private final CompanionService service;
    private final Contacts contacts;
    private final SmsStore store;
    private final Sender sender;
    private final Watcher watcher;
    private final Exporter exporter;
    private final Restore restore;

    Backend(CompanionService service) {
        this.service = service;
        this.contacts = new Contacts(service);
        this.store = new SmsStore(service, contacts);
        this.sender = new Sender(service);
        this.watcher = new Watcher(service, this);
        this.exporter = new Exporter(service, store);
        this.restore = new Restore(service, this);
    }

    void start() {
        watcher.start();
    }

    void stop() {
        watcher.stop();
    }

    SmsStore store() {
        return store;
    }

    Sender sender() {
        return sender;
    }

    Restore restore() {
        return restore;
    }

    ScheduledExecutorService timers() {
        return service.timers();
    }

    /** An event for one session (by its number), if it is still there. */
    void toSession(long number, String event, Map<String, Object> data) {
        for (Session s : service.sessions()) {
            if (s.number == number) {
                s.event(event, data);
                return;
            }
        }
    }

    /** An event for every session subscribed to it. */
    void broadcast(String event, Map<String, Object> data) {
        for (Session s : service.sessions()) {
            if (s.authenticated() && s.subscribedTo(event)) {
                s.event(event, data);
            }
        }
    }

    boolean anySubscribed(String event) {
        for (Session s : service.sessions()) {
            if (s.subscribedTo(event)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<String, Object> hello(Session session, Requests.Hello hello) {
        L.i("session " + session.number + ": hello from a client");
        return Json.obj(
                "revision", (long) Protocol.REVISION,
                "app", Protocol.APP,
                "app_version", BuildConfig.VERSION_NAME,
                "app_version_code", (long) BuildConfig.VERSION_CODE,
                "package", service.getPackageName(),
                "android", Json.obj("sdk_int", (long) Build.VERSION.SDK_INT, "release", Build.VERSION.RELEASE,
                        "manufacturer", Build.MANUFACTURER, "model", Build.MODEL),
                "capabilities", new ArrayList<Object>(Protocol.CAPABILITIES),
                "limits", Protocol.limits(),
                "default_sms_app", Perms.isDefaultSmsApp(service),
                "permissions", Perms.state(service),
                "sends", Protocol.sends(Sends.get(service).store));
    }

    /** sms.send's client_ref is "checking" from the moment the request is read. */
    @Override
    public void arrived(Session session, Session.Call call) {
        if ("sms.send".equals(call.method)) {
            sender.arrived(call, (Requests.SmsSend) call.value);
        }
    }

    /** The notification names the client once it has proved itself. */
    @Override
    public void authenticated(Session session) {
        timers().execute(service::updateNotification);
    }

    @Override
    public void closed(Session session) {
        exporter.closed(session);
        service.sessionClosed(session);
    }

    @Override
    public void handle(Session session, Session.Call call) throws Exception {
        Object v = call.value;
        switch (call.method) {
            case "status":
                call.ok(Json.obj("default_sms_app", Perms.isDefaultSmsApp(service),
                        "default_sms_package", Perms.defaultSmsPackage(service),
                        "permissions", Perms.state(service),
                        "subscribed", session.subscriptions(),
                        "sessions", (long) service.sessions().size(),
                        "sends", Protocol.sends(Sends.get(service).store)));
                return;
            case "subscriptions.list":
                call.ok(subscriptions());
                return;
            case "contacts.list": {
                Requests.Page p = (Requests.Page) v;
                call.ok(contacts.list(p.offset, p.limit));
                return;
            }
            case "contacts.photo": {
                Requests.ContactPhoto p = (Requests.ContactPhoto) v;
                SmsStore.PartData photo = contacts.photo(p.contactId, p.full);
                call.okWithBlob(photo.data, photo.mime, "photo", Json.obj("contact_id", p.contactId));
                return;
            }
            case "threads.list": {
                Perms.require(service, "READ_SMS");
                Requests.Page p = (Requests.Page) v;
                call.ok(store.threads(p.offset, p.limit));
                return;
            }
            case "messages.list": {
                Perms.require(service, "READ_SMS");
                Requests.MessagesList m = (Requests.MessagesList) v;
                call.ok(page(store.messages(m.threadId, m.limit, m.before)));
                return;
            }
            case "messages.search": {
                Perms.require(service, "READ_SMS");
                call.ok(page(store.search((Requests.Search) v)));
                return;
            }
            case "message.get": {
                Perms.require(service, "READ_SMS");
                Requests.Target t = (Requests.Target) v;
                call.ok(Json.obj("message", store.get(t.kind, t.id)));
                return;
            }
            case "message.mark_read":
                Perms.require(service, "READ_SMS");
                call.ok(Json.obj("updated", (long) store.markRead((Requests.Target) v)));
                return;
            case "message.delete":
                Perms.require(service, "READ_SMS");
                call.ok(Json.obj("deleted", (long) store.delete((Requests.Target) v)));
                return;
            case "sms.send":
                sender.send(call, (Requests.SmsSend) v);
                return;
            case "sms.status": {
                Requests.SendQuery q = (Requests.SendQuery) v;
                SendTracker tracker = Sends.get(service).tracker;
                // (by client_ref, not_found carries the store and dropped_through)
                Map<String, Object> st = q.clientRef != null ? tracker.statusByRef(q.clientRef) : tracker.status(q.sendId);
                if (st == null) {
                    throw new RequestException(ErrorCode.NOT_FOUND, "no such send (never accepted, or older than keep_ms)");
                }
                call.ok(st);
                return;
            }
            case "mms.parts": {
                Perms.require(service, "READ_SMS");
                long id = (Long) v;
                store.get(Requests.KIND_MMS, id);
                call.ok(Json.obj("message_id", id, "parts", new ArrayList<Object>(store.parts(id))));
                return;
            }
            case "mms.part_data": {
                Perms.require(service, "READ_SMS");
                long id = (Long) v;
                SmsStore.PartData d = store.partData(id);
                call.okWithBlob(d.data, d.mime, "data", Json.obj("part_id", id));
                return;
            }
            case "backup.export":
                exporter.export(call, (Requests.Export) v);
                return;
            case "backup.cancel":
                exporter.cancel(call, (Long) v);
                return;
            case "backup.ack":
                exporter.ack(call, (Requests.Ack) v);
                return;
            case "backup.restore.begin":
                restore.begin(call, (Requests.RestoreBegin) v);
                return;
            case "backup.restore.chunk":
                restore.chunk(call, (Requests.RestoreChunk) v);
                return;
            case "backup.restore.end":
                restore.end(call, (Long) v);
                return;
            default:
                throw new RequestException(ErrorCode.UNKNOWN_METHOD, "no such method");
        }
    }

    private static Map<String, Object> page(MessageOrder.Page p) {
        return Json.obj("messages", new ArrayList<Object>(p.messages), "next", p.next == null ? null : p.next.toJson());
    }

    private Map<String, Object> subscriptions() throws RequestException {
        Perms.require(service, "READ_PHONE_STATE");
        SubscriptionManager sm = service.getSystemService(SubscriptionManager.class);
        List<Object> out = new ArrayList<>();
        int defaultSms = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (sm != null) {
            List<SubscriptionInfo> list;
            try {
                list = sm.getActiveSubscriptionInfoList();
            } catch (SecurityException e) {
                throw RequestException.permission("READ_PHONE_STATE");
            }
            if (list != null) {
                for (SubscriptionInfo i : list) {
                    out.add(Json.obj(
                            "sub_id", (long) i.getSubscriptionId(),
                            "slot_index", (long) i.getSimSlotIndex(),
                            "display_name", i.getDisplayName() == null ? null : i.getDisplayName().toString(),
                            "carrier_name", i.getCarrierName() == null ? null : i.getCarrierName().toString(),
                            "country_iso", i.getCountryIso(),
                            "is_default_sms", i.getSubscriptionId() == defaultSms));
                }
            }
        }
        return Json.obj("subscriptions", out, "default_sms_sub_id", (long) (defaultSms < 0 ? -1 : defaultSms));
    }
}

// SPDX-License-Identifier: MIT
package me.jxl.kiosk.plugins.spectrumvisualizer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure digital UPnP/Sonos speaker discovery and real-time HTTP stream buffer delay tracker.
 *
 * <p>Discovers all Sonos speakers on the local network without hardcoded names or IPs,
 * tracks the active speaker by hardware UUID (RINCON_...) so speaker renames or DHCP IP
 * changes never break selection, and measures the exact buffer offset between the local
 * Sendspin stream and physical Sonos playback via UPnP RelTime second-flips.
 */
public final class SpeakerSync {
    public static final int DEFAULT_UGP_DELAY_MS = 3980;
    public static final int MIN_VALID_DELAY_MS = 200;
    public static final int MAX_VALID_DELAY_MS = 10000;

    private static final Pattern ZONE_GROUP_PATTERN = Pattern.compile(
            "<ZoneGroup\\b([^>]*)>(.*?)</ZoneGroup>", Pattern.DOTALL);
    private static final Pattern MEMBER_TAG_PATTERN = Pattern.compile(
            "<ZoneGroupMember\\b([^>]*)/?>", Pattern.DOTALL);
    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "(\\w+)=\"([^\"]*)\"");
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile(
            "^https?://([^/:]+)");

    public static final class SpeakerInfo {
        public final String uuid;
        public final String name;
        public final String ip;
        public final boolean coordinator;
        public final String groupId;

        public SpeakerInfo(String uuid, String name, String ip, boolean coordinator, String groupId) {
            this.uuid = uuid == null ? "" : uuid;
            this.name = name == null ? "" : name;
            this.ip = ip == null ? "" : ip;
            this.coordinator = coordinator;
            this.groupId = groupId == null ? "" : groupId;
        }
    }

    public static final class PlaybackSnapshot {
        public final String transportState;
        public final String trackUri;
        public final int relTimeSeconds;
        public final boolean isMaStream;

        public PlaybackSnapshot(String transportState, String trackUri, int relTimeSeconds, boolean isMaStream) {
            this.transportState = transportState == null ? "" : transportState;
            this.trackUri = trackUri == null ? "" : trackUri;
            this.relTimeSeconds = relTimeSeconds;
            this.isMaStream = isMaStream;
        }
    }

    private final LongSupplier clockMs;
    private volatile String targetSpec = "auto";
    private volatile int manualOffsetMs = 0;
    private volatile int measuredDelayMs = 0;
    private volatile int fallbackUgpDelayMs = DEFAULT_UGP_DELAY_MS;
    private volatile String state = "IDLE";
    private volatile String activeSpeakerUuid = "";
    private volatile String activeSpeakerName = "";
    private volatile String activeSpeakerIp = "";
    private volatile List<SpeakerInfo> discoveredSpeakers = Collections.emptyList();

    private volatile long streamStartAtMs = 0L;
    private volatile double streamStartHeadSeconds = Double.NaN;
    private volatile int lastRelSeconds = -1;
    private volatile String lastTrackUri = "";
    private Thread workerThread;
    private volatile boolean running;

    public SpeakerSync() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    public SpeakerSync(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    public void setTargetSpec(String spec) {
        String normalized = spec == null || spec.trim().isEmpty() ? "auto" : spec.trim();
        if (!normalized.equals(this.targetSpec)) {
            this.targetSpec = normalized;
            if (isOffSpec(normalized)) {
                measuredDelayMs = 0;
                state = "OFF";
            } else if ("OFF".equals(state)) {
                state = "IDLE";
            }
        }
    }

    public String getTargetSpec() {
        return targetSpec;
    }

    public void setManualOffsetMs(int offsetMs) {
        this.manualOffsetMs = Math.max(-5000, Math.min(5000, offsetMs));
    }

    public int getManualOffsetMs() {
        return manualOffsetMs;
    }

    public void setFallbackUgpDelayMs(int delayMs) {
        if (delayMs >= MIN_VALID_DELAY_MS && delayMs <= MAX_VALID_DELAY_MS) {
            this.fallbackUgpDelayMs = delayMs;
        }
    }

    public int getMeasuredDelayMs() {
        return isOffSpec(targetSpec) ? 0 : measuredDelayMs;
    }

    public int getEffectiveDelayMs() {
        int base = isOffSpec(targetSpec) ? 0 : measuredDelayMs;
        return Math.max(0, Math.min(10000, base + manualOffsetMs));
    }

    public String getState() {
        return state;
    }

    public String getActiveSpeakerName() {
        return activeSpeakerName;
    }

    public String getActiveSpeakerUuid() {
        return activeSpeakerUuid;
    }

    public String getActiveSpeakerIp() {
        return activeSpeakerIp;
    }

    public List<SpeakerInfo> getDiscoveredSpeakers() {
        return discoveredSpeakers;
    }

    public synchronized void onStreamStart(long startedAtMs, double audioTrackHeadSeconds) {
        this.streamStartAtMs = startedAtMs;
        this.streamStartHeadSeconds = audioTrackHeadSeconds;
        this.lastRelSeconds = -1;
        if (!isOffSpec(targetSpec)) {
            this.state = "MEASURING";
        }
    }

    public synchronized void onStreamStop() {
        this.streamStartAtMs = 0L;
        this.streamStartHeadSeconds = Double.NaN;
        this.lastRelSeconds = -1;
        if (!isOffSpec(targetSpec)) {
            this.state = "IDLE";
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        workerThread = new Thread(this::runLoop, "speaker-sync-upnp");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        Thread t = workerThread;
        workerThread = null;
        if (t != null) t.interrupt();
    }

    private void runLoop() {
        long lastDiscoveryMs = 0L;
        while (running) {
            try {
                if (isOffSpec(targetSpec)) {
                    measuredDelayMs = 0;
                    state = "OFF";
                    sleepMs(1000L);
                    continue;
                }
                long now = clockMs.getAsLong();
                if (discoveredSpeakers.isEmpty() || now - lastDiscoveryMs > 30000L) {
                    List<SpeakerInfo> found = discoverSpeakers();
                    if (!found.isEmpty()) {
                        discoveredSpeakers = found;
                        lastDiscoveryMs = clockMs.getAsLong();
                    }
                }

                SpeakerInfo target = resolveTargetSpeaker(discoveredSpeakers, targetSpec);
                if (target == null) {
                    sleepMs(1500L);
                    continue;
                }
                activeSpeakerUuid = target.uuid;
                activeSpeakerName = target.name;
                activeSpeakerIp = target.ip;

                PlaybackSnapshot snap = queryPlayback(target.ip, 800);
                if (snap == null || !"PLAYING".equalsIgnoreCase(snap.transportState)) {
                    lastRelSeconds = -1;
                    if (!"LOCKED".equals(state)) {
                        state = "IDLE";
                    }
                    sleepMs(500L);
                    continue;
                }

                // If speaker just started a new stream or reset RelTime to 0..1
                if (!snap.trackUri.equals(lastTrackUri) ||
                        (lastRelSeconds > 2 && snap.relTimeSeconds >= 0 && snap.relTimeSeconds <= 1)) {
                    lastTrackUri = snap.trackUri;
                    lastRelSeconds = snap.relTimeSeconds;
                }

                // Measure exact sub-second RelTime flip when within the initial window
                if (snap.relTimeSeconds >= 0 && snap.relTimeSeconds <= 20) {
                    int locked = measureTickFlip(target.ip, snap);
                    if (locked >= MIN_VALID_DELAY_MS && locked <= MAX_VALID_DELAY_MS) {
                        measuredDelayMs = locked;
                        fallbackUgpDelayMs = locked;
                        state = "LOCKED";
                        sleepMs(2000L);
                        continue;
                    }
                }

                // Mid-stream fallback: if Sonos is playing a Music Assistant UGP stream and we
                // attached after the first 20 seconds, apply the last verified UGP buffer delay.
                if (snap.isMaStream && measuredDelayMs <= 0) {
                    measuredDelayMs = fallbackUgpDelayMs;
                    state = "ESTIMATED";
                }
                sleepMs(1000L);
            } catch (Throwable ignored) {
                sleepMs(1000L);
            }
        }
    }

    private int measureTickFlip(String ip, PlaybackSnapshot initial) {
        int baseRel = initial.relTimeSeconds;
        if (baseRel < 0) return -1;
        long deadline = clockMs.getAsLong() + 1350L;
        while (running && clockMs.getAsLong() < deadline) {
            long beforeMs = clockMs.getAsLong();
            PlaybackSnapshot poll = queryPositionOnly(ip, 400);
            long afterMs = clockMs.getAsLong();
            if (poll == null || poll.relTimeSeconds < 0) break;
            if (poll.relTimeSeconds == baseRel + 1) {
                long tickAtMs = (beforeMs + afterMs) / 2L;
                long t0 = streamStartAtMs;
                if (t0 > 0L) {
                    long elapsedSinceStartMs = tickAtMs - t0;
                    int candidateMs = (int) (elapsedSinceStartMs - poll.relTimeSeconds * 1000L);
                    if (candidateMs >= MIN_VALID_DELAY_MS && candidateMs <= MAX_VALID_DELAY_MS) {
                        lastRelSeconds = poll.relTimeSeconds;
                        return candidateMs;
                    }
                }
                break;
            } else if (poll.relTimeSeconds != baseRel) {
                break;
            }
            sleepMs(40L);
        }
        return -1;
    }

    public static SpeakerInfo resolveTargetSpeaker(List<SpeakerInfo> speakers, String spec) {
        if (speakers == null || speakers.isEmpty() || isOffSpec(spec)) return null;
        String clean = spec == null ? "auto" : spec.trim();
        if (!"auto".equalsIgnoreCase(clean)) {
            for (SpeakerInfo s : speakers) {
                if (s.uuid.equalsIgnoreCase(clean)) return s;
            }
            for (SpeakerInfo s : speakers) {
                if (s.name.equalsIgnoreCase(clean)) return s;
            }
            for (SpeakerInfo s : speakers) {
                if (s.ip.equalsIgnoreCase(clean)) return s;
            }
        }
        List<SpeakerInfo> coordinators = new ArrayList<>();
        for (SpeakerInfo s : speakers) {
            if (s.coordinator) coordinators.add(s);
        }
        if (coordinators.isEmpty()) coordinators = speakers;

        SpeakerInfo playingFallback = null;
        for (SpeakerInfo coord : coordinators) {
            PlaybackSnapshot snap = queryPlayback(coord.ip, 500);
            if (snap == null) continue;
            boolean active = "PLAYING".equalsIgnoreCase(snap.transportState) ||
                    "TRANSITIONING".equalsIgnoreCase(snap.transportState);
            if (active && snap.isMaStream) {
                return coord;
            }
            if (active && playingFallback == null) {
                playingFallback = coord;
            }
        }
        return playingFallback != null ? playingFallback : coordinators.get(0);
    }

    public static boolean isOffSpec(String spec) {
        if (spec == null) return false;
        String s = spec.trim().toLowerCase(Locale.ROOT);
        return "off".equals(s) || "none".equals(s) || "false".equals(s) || "0".equals(s) || "fra".equals(s);
    }

    public static List<SpeakerInfo> discoverSpeakers() {
        List<String> candidateIps = discoverCandidateIps();
        for (String ip : candidateIps) {
            List<SpeakerInfo> parsed = fetchZoneGroups(ip, 900);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        return Collections.emptyList();
    }

    private static List<String> discoverCandidateIps() {
        List<String> result = new ArrayList<>();
        // 1. Fast SSDP multicast search for Sonos ZonePlayers
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(350);
            String msg = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n";
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, InetAddress.getByName("239.255.255.250"), 1900);
            socket.send(packet);
            byte[] buf = new byte[2048];
            long deadline = System.nanoTime() / 1_000_000L + 380L;
            while (System.nanoTime() / 1_000_000L < deadline) {
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                socket.receive(reply);
                String ip = reply.getAddress().getHostAddress();
                if (ip != null && !result.contains(ip)) {
                    result.add(ip);
                    break; // Any single Sonos speaker gives us the entire ZoneGroupTopology!
                }
            }
        } catch (Throwable ignored) {}
        if (!result.isEmpty()) return result;

        // 2. Fallback: quick parallel TCP port 1400 sweep on local /24 subnet
        String prefix = localSubnetPrefix();
        if (prefix.isEmpty()) return result;
        ExecutorService pool = Executors.newFixedThreadPool(24);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int host = 1; host <= 254; host++) {
                final String ip = prefix + host;
                tasks.add(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(ip, 1400), 120);
                        return ip;
                    } catch (Throwable ignored) {
                        return null;
                    }
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks, 1200, TimeUnit.MILLISECONDS);
            for (Future<String> f : futures) {
                if (f.isDone() && !f.isCancelled()) {
                    try {
                        String ip = f.get();
                        if (ip != null) result.add(ip);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    private static String localSubnetPrefix() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        int lastDot = ip.lastIndexOf('.');
                        if (lastDot > 0) return ip.substring(0, lastDot + 1);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public static List<SpeakerInfo> fetchZoneGroups(String ip, int timeoutMs) {
        String body = "<u:GetZoneGroupState xmlns:u=\"urn:schemas-upnp-org:service:ZoneGroupTopology:1\"/>";
        String xml = soapPost(
                ip,
                "/ZoneGroupTopology/Control",
                "urn:schemas-upnp-org:service:ZoneGroupTopology:1#GetZoneGroupState",
                body,
                timeoutMs);
        if (xml.isEmpty()) return Collections.emptyList();
        return parseZoneGroupStateXml(xml);
    }

    public static List<SpeakerInfo> parseZoneGroupStateXml(String outerXml) {
        if (outerXml == null || outerXml.isEmpty()) return Collections.emptyList();
        String inner = extractTagText(outerXml, "ZoneGroupState");
        String source = inner.isEmpty() ? outerXml : unescapeXml(inner);

        Map<String, SpeakerInfo> byUuid = new LinkedHashMap<>();
        Matcher groupMatcher = ZONE_GROUP_PATTERN.matcher(source);
        while (groupMatcher.find()) {
            Map<String, String> groupAttrs = parseAttributes(groupMatcher.group(1));
            String coordinatorUuid = groupAttrs.getOrDefault("Coordinator", "");
            String groupId = groupAttrs.getOrDefault("ID", "");
            String groupBody = groupMatcher.group(2);

            Matcher memberMatcher = MEMBER_TAG_PATTERN.matcher(groupBody);
            while (memberMatcher.find()) {
                Map<String, String> attrs = parseAttributes(memberMatcher.group(1));
                if ("1".equals(attrs.get("Invisible"))) continue;
                String uuid = attrs.getOrDefault("UUID", "").trim();
                String name = attrs.getOrDefault("ZoneName", "").trim();
                String location = attrs.getOrDefault("Location", "").trim();
                String ip = extractIpFromLocation(location);
                if (uuid.isEmpty() || name.isEmpty() || ip.isEmpty()) continue;
                boolean isCoord = uuid.equals(coordinatorUuid);
                SpeakerInfo existing = byUuid.get(uuid);
                if (existing == null || isCoord) {
                    byUuid.put(uuid, new SpeakerInfo(uuid, name, ip, isCoord, groupId));
                }
            }
        }
        List<SpeakerInfo> list = new ArrayList<>(byUuid.values());
        Collections.sort(list, new Comparator<SpeakerInfo>() {
            @Override
            public int compare(SpeakerInfo a, SpeakerInfo b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return list;
    }

    public static PlaybackSnapshot queryPlayback(String ip, int timeoutMs) {
        String transportBody = "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID></u:GetTransportInfo>";
        String transportXml = soapPost(
                ip,
                "/MediaRenderer/AVTransport/Control",
                "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo",
                transportBody,
                timeoutMs);
        if (transportXml.isEmpty()) return null;
        String state = extractTagText(transportXml, "CurrentTransportState");

        PlaybackSnapshot pos = queryPositionOnly(ip, timeoutMs);
        if (pos == null) return new PlaybackSnapshot(state, "", -1, false);
        return new PlaybackSnapshot(state, pos.trackUri, pos.relTimeSeconds, pos.isMaStream);
    }

    public static PlaybackSnapshot queryPositionOnly(String ip, int timeoutMs) {
        String posBody = "<u:GetPositionInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID></u:GetPositionInfo>";
        String posXml = soapPost(
                ip,
                "/MediaRenderer/AVTransport/Control",
                "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo",
                posBody,
                timeoutMs);
        if (posXml.isEmpty()) return null;
        String uri = unescapeXml(extractTagText(posXml, "TrackURI"));
        String relTime = extractTagText(posXml, "RelTime");
        int sec = parseRelTimeSeconds(relTime);
        String lowerUri = uri.toLowerCase(Locale.ROOT);
        boolean isMa = lowerUri.contains("/ugp/") ||
                lowerUri.contains(":8097/") ||
                lowerUri.contains(":8095/") ||
                lowerUri.startsWith("x-rincon-mp3radio://");
        return new PlaybackSnapshot("PLAYING", uri, sec, isMa);
    }

    public static int parseRelTimeSeconds(String relTime) {
        if (relTime == null) return -1;
        String clean = relTime.trim();
        if (clean.isEmpty() || "NOT_IMPLEMENTED".equalsIgnoreCase(clean)) return -1;
        String[] parts = clean.split(":");
        if (parts.length != 3) return -1;
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            double s = Double.parseDouble(parts[2].trim());
            if (h < 0 || m < 0 || s < 0) return -1;
            return h * 3600 + m * 60 + (int) Math.floor(s);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String soapPost(String ip, String path, String soapAction, String bodyXml, int timeoutMs) {
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" + bodyXml + "</s:Body></s:Envelope>";
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":1400" + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");
            byte[] payload = envelope.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            if (conn.getResponseCode() != 200) return "";
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (bos.size() + n > 256 * 1024) break;
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String extractTagText(String xml, String tag) {
        int start = xml.indexOf("<" + tag + ">");
        if (start < 0) return "";
        start += tag.length() + 2;
        int end = xml.indexOf("</" + tag + ">", start);
        if (end < 0) return "";
        return xml.substring(start, end).trim();
    }

    private static Map<String, String> parseAttributes(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = ATTR_PATTERN.matcher(raw);
        while (m.find()) {
            map.put(m.group(1), unescapeXml(m.group(2)));
        }
        return map;
    }

    private static String extractIpFromLocation(String location) {
        Matcher m = HOST_PORT_PATTERN.matcher(location);
        return m.find() ? m.group(1) : "";
    }

    private static String unescapeXml(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

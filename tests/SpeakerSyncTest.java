package me.jxl.kiosk.plugins.spectrumvisualizer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SpeakerSyncTest {
    private static final String SAMPLE_ZONE_XML =
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:GetZoneGroupStateResponse xmlns:u=\"urn:schemas-upnp-org:service:ZoneGroupTopology:1\">" +
            "<ZoneGroupState>&lt;ZoneGroupState&gt;&lt;ZoneGroups&gt;" +
            "&lt;ZoneGroup Coordinator=\"RINCON_000E585DCF6801400\" ID=\"RINCON_000E585DCF6801400:1\"&gt;" +
            "&lt;ZoneGroupMember UUID=\"RINCON_000E585DCF6801400\" " +
            "Location=\"http://192.168.0.44:1400/xml/device_description.xml\" " +
            "ZoneName=\"Stueetagen (Sonos)\" Invisible=\"0\"/&gt;" +
            "&lt;/ZoneGroup&gt;" +
            "&lt;ZoneGroup Coordinator=\"RINCON_000E5872D8AC01400\" ID=\"RINCON_000E5872D8AC01400:2\"&gt;" +
            "&lt;ZoneGroupMember UUID=\"RINCON_000E5872D8AC01400\" " +
            "Location=\"http://192.168.0.159:1400/xml/device_description.xml\" " +
            "ZoneName=\"Bad\" Invisible=\"0\"/&gt;" +
            "&lt;/ZoneGroup&gt;" +
            "&lt;/ZoneGroups&gt;&lt;/ZoneGroupState&gt;</ZoneGroupState>" +
            "</u:GetZoneGroupStateResponse></s:Body></s:Envelope>";

    public static void main(String[] args) throws Exception {
        if (SpeakerSync.parseRelTimeSeconds("0:00:00") != 0)
            throw new AssertionError("Expected 0s");
        if (SpeakerSync.parseRelTimeSeconds("0:01:34") != 94)
            throw new AssertionError("Expected 94s");
        if (SpeakerSync.parseRelTimeSeconds("NOT_IMPLEMENTED") != -1)
            throw new AssertionError("Expected -1 for NOT_IMPLEMENTED");

        List<SpeakerSync.SpeakerInfo> speakers = SpeakerSync.parseZoneGroupStateXml(SAMPLE_ZONE_XML);
        if (speakers.size() != 2)
            throw new AssertionError("Expected 2 speakers, got " + speakers.size());
        if (!"Bad".equals(speakers.get(0).name) || !"Stueetagen (Sonos)".equals(speakers.get(1).name))
            throw new AssertionError("Unexpected speaker names: " + speakers.get(0).name + ", " + speakers.get(1).name);

        // Verify UUID persistence survives speaker rename and IP change
        SpeakerSync.SpeakerInfo byName = SpeakerSync.resolveTargetSpeaker(speakers, "Stueetagen (Sonos)");
        if (byName == null || !"RINCON_000E585DCF6801400".equals(byName.uuid))
            throw new AssertionError("Must resolve speaker by name");

        List<SpeakerSync.SpeakerInfo> renamed = java.util.Arrays.asList(
                new SpeakerSync.SpeakerInfo("RINCON_000E585DCF6801400", "Alrum Nyt Navn", "192.168.0.99", true, "g1")
        );
        SpeakerSync.SpeakerInfo byUuidAfterRename = SpeakerSync.resolveTargetSpeaker(renamed, "RINCON_000E585DCF6801400");
        if (byUuidAfterRename == null || !"Alrum Nyt Navn".equals(byUuidAfterRename.name) || !"192.168.0.99".equals(byUuidAfterRename.ip))
            throw new AssertionError("UUID selection must survive speaker rename and IP change");

        if (SpeakerSync.resolveTargetSpeaker(speakers, "off") != null)
            throw new AssertionError("off must return null target");

        AtomicLong fakeClockMs = new AtomicLong(10000L);
        SpeakerSync sync = new SpeakerSync(fakeClockMs::get);
        sync.setFallbackUgpDelayMs(3980);
        sync.setManualOffsetMs(120);
        sync.onStreamStart(10000L, 0.0);
        if (!"MEASURING".equals(sync.getState()))
            throw new AssertionError("Expected MEASURING state on stream start");
        sync.setTargetSpec("off");
        if (sync.getEffectiveDelayMs() != 120)
            throw new AssertionError("Expected manual offset only when target is off");

        System.out.println("SpeakerSync regression checks passed");
    }
}

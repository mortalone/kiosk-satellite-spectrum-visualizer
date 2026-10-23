"""Execute the production stream lifecycle block with a fake AudioTrack clock."""
from pathlib import Path
import subprocess, tempfile
source = Path("src/me/jxl/kiosk/plugins/spectrumvisualizer/SpectrumVisualizerPlugin.java").read_text()
start = source.index("            // A quiet musical passage")
end = source.index("            persistSpeakerSyncStateIfNeeded();", start)
block = source[start:end]
java = """public class StreamContinuityTest {
 static class Sync {int starts, stops; void onStreamStart(long n,double h){starts++;} void onStreamStop(){stops++;}}
 final Sync speakerSync=new Sync(); long lastActiveSignalAtMs; int lastTrackHeadPos=-1, clears;
 void clearDelayedFrames(){clears++;}
 void frame(long now,int headPos,int peak){double headSec=headPos/48000.0;
""" + block + """}
 public static void main(String[] args){
 StreamContinuityTest t=new StreamContinuityTest();
 t.frame(1000,48000,20);
 for(int i=1;i<=120;i++)t.frame(1000+i*50,48000+i*2400,0);
 if(t.speakerSync.starts!=1||t.clears!=1||t.speakerSync.stops!=0)throw new AssertionError("Six seconds of silence with advancing audio must retain buffer");
 t.frame(7100,340800,30);
 if(t.speakerSync.starts!=1)throw new AssertionError("Music returning must not reset sync");
 t.frame(7200,100,20);
 if(t.speakerSync.starts!=2)throw new AssertionError("Real head reset must restart sync");
 t.frame(9600,100,0);
 if(t.speakerSync.stops!=1)throw new AssertionError("Stopped head and silence must stop stream");
 System.out.println("Stream continuity passed");
 }}"""
with tempfile.TemporaryDirectory() as directory:
 p=Path(directory)/"StreamContinuityTest.java";p.write_text(java)
 subprocess.run(["javac","--release","8",str(p)],check=True)
 subprocess.run(["java","-cp",directory,"StreamContinuityTest"],check=True)

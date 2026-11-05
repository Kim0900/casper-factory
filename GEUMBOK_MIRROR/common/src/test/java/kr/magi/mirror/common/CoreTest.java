package kr.magi.mirror.common;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class CoreTest {
    @Test public void retryStopsAfterThreeAttemptsAndDeadline() {
        RetryBudget budget=new RetryBudget(1000);
        assertEquals(250,budget.nextDelay(RetryBudget.Failure.SOCKET,1000));
        assertEquals(500,budget.nextDelay(RetryBudget.Failure.SOCKET,1250));
        assertEquals(1000,budget.nextDelay(RetryBudget.Failure.SOCKET,1750));
        assertEquals(-1,budget.nextDelay(RetryBudget.Failure.SOCKET,2750));
        assertEquals(-1,new RetryBudget(0).nextDelay(RetryBudget.Failure.SOCKET,14800));
        assertEquals(500,new RetryBudget(0).connectTimeout(14500));
        assertEquals(0,new RetryBudget(0).connectTimeout(15001));
    }
    @Test public void terminalEventsNeverRetry() {
        for(RetryBudget.Failure reason:RetryBudget.Failure.values()) {
            if(reason!=RetryBudget.Failure.SOCKET) assertEquals(-1,new RetryBudget(0).nextDelay(reason,0));
        }
    }
    @Test public void fitFillAndRegionMaintainAspectAcrossTwentyResizes() {
        for(int iteration=0;iteration<20;iteration++) {
            int width=iteration%2==0?300:700;
            Geometry fit=Geometry.calculate(1280,720,width,800,Geometry.Mode.FIT,.5f,.5f,2);
            assertTrue(1280*fit.scale<=width+.01); assertTrue(720*fit.scale<=800+.01);
            Geometry fill=Geometry.calculate(1280,720,width,800,Geometry.Mode.FILL,.5f,.5f,2);
            assertTrue(1280*fill.scale>=width-.01); assertTrue(720*fill.scale>=800-.01);
            Geometry region=Geometry.calculate(1280,720,width,800,Geometry.Mode.REGION,0,1,2);
            assertTrue(Float.isFinite(region.translateX)); assertTrue(region.translateY<=0);
        }
    }
    @Test(expected=IllegalArgumentException.class) public void invalidRegionRejected() {
        Geometry.calculate(1280,720,300,800,Geometry.Mode.REGION,Float.NaN,.5f,2);
    }
    @Test public void framingRoundTripAndNoReverseCommands() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Wire.write(new DataOutputStream(bytes),new Wire.Packet(Wire.FRAME,1,15,99,new byte[]{1,2,3}));
        Wire.Packet packet=Wire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(15,packet.sequence); assertEquals(99,packet.presentationUs); assertEquals(1,packet.flags);
        assertArrayEquals(new byte[]{1,2,3},packet.data);
        for(int type:new int[]{0,3,255}) {
            try {Wire.write(new DataOutputStream(new ByteArrayOutputStream()),new Wire.Packet(type,0,0,0,new byte[]{1}));fail();}
            catch(Wire.ProtocolException expected) { }
        }
    }
    @Test public void oversizedAndTruncatedPacketsRejectedBeforeAllocation() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(Wire.MAGIC);out.writeByte(Wire.FRAME);out.writeByte(1);out.writeLong(1);out.writeLong(1);out.writeInt(Integer.MAX_VALUE);
        try {Wire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));fail();}
        catch(Wire.ProtocolException expected) { }
        bytes.reset();Wire.write(new DataOutputStream(bytes),new Wire.Packet(Wire.FRAME,1,1,1,new byte[]{1,2}));
        byte[] truncated=java.util.Arrays.copyOf(bytes.toByteArray(),bytes.size()-1);
        try {Wire.read(new DataInputStream(new ByteArrayInputStream(truncated)));fail();}
        catch(EOFException expected) { }
    }
}

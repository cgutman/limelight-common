package com.limelight.nvstream.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.LinkedBlockingQueue;

import com.limelight.LimeLog;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.av.ConnectionStatusListener;

public class ControlStream implements ConnectionStatusListener {
	
	public static final int PORT = 47995;
	
	public static final int CONTROL_TIMEOUT = 5000;
	
	public static final short PTYPE_KEEPALIVE = 0x13ff;
	public static final short PPAYLEN_KEEPALIVE = 0x0000;
	
	public static final short PTYPE_HEARTBEAT = 0x1401;
	public static final short PPAYLEN_HEARTBEAT = 0x0000;
	
	public static final short PTYPE_1405 = 0x1405;
	public static final short PPAYLEN_1405 = 0x0000;
	
	public static final short PTYPE_RESYNC = 0x1404;
	public static final short PPAYLEN_RESYNC = 16;
	
	public static final short PTYPE_CONFIG = 0x1205;
	public static final short PPAYLEN_CONFIG = 0x0004;
	
	
	public static final short PTYPE_JITTER = 0x140c;
	public static final short PPAYLEN_JITTER = 0x10;
	
	private int seqNum;
	
	private NvConnectionListener listener;
	private InetAddress host;
	private Config config;
	
	public static final int LOSS_PERIOD_MS = 15000;
	public static final int MAX_LOSS_COUNT_IN_PERIOD = 2;
	public static final int MAX_SLOW_SINK_COUNT = 2;
	public static final int MESSAGE_DELAY_FACTOR = 3;
	
	private long lossTimestamp;
	private int lossCount;
	private int slowSinkCount;
	
	private Socket s;
	private InputStream in;
	private OutputStream out;
	
	private Thread heartbeatThread;
	private Thread jitterThread;
	private Thread resyncThread;
	private LinkedBlockingQueue<int[]> invalidReferenceFrameTuples = new LinkedBlockingQueue<int[]>();
	private boolean aborting = false;
	
	public ControlStream(InetAddress host, NvConnectionListener listener, StreamConfiguration streamConfig)
	{
		this.listener = listener;
		this.host = host;
		this.config = new Config(streamConfig);
	}
	
	public void initialize() throws IOException
	{
		s = new Socket();
		s.setTcpNoDelay(true);
		s.connect(new InetSocketAddress(host, PORT), CONTROL_TIMEOUT);
		in = s.getInputStream();
		out = s.getOutputStream();
	}
	
	private void sendPacket(NvCtlPacket packet) throws IOException
	{
		out.write(packet.toWire());
		out.flush();
	}
	
	private ControlStream.NvCtlResponse sendAndGetReply(NvCtlPacket packet) throws IOException
	{
		sendPacket(packet);
		return new NvCtlResponse(in);
	}
	
	private void sendJitter() throws IOException
	{
		ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		
		bb.putInt(0);
		bb.putInt(77);
		bb.putInt(888);
		bb.putInt(seqNum += 2);

		sendPacket(new NvCtlPacket(PTYPE_JITTER, PPAYLEN_JITTER, bb.array()));
	}
	
	public void abort()
	{
		if (aborting) {
			return;
		}
		
		aborting = true;
		
		try {
			s.close();
		} catch (IOException e) {}
		
		if (jitterThread != null) {
			jitterThread.interrupt();
			
			try {
				jitterThread.join();
			} catch (InterruptedException e) {}
		}
		
		if (heartbeatThread != null) {
			heartbeatThread.interrupt();
			
			try {
				heartbeatThread.join();
			} catch (InterruptedException e) {}
		}
		
		if (resyncThread != null) {
			resyncThread.interrupt();
			
			try {
				resyncThread.join();
			} catch (InterruptedException e) {}
		}
	}
	
	public void start() throws IOException
	{
		// Use a finite timeout during the handshake process
		s.setSoTimeout(CONTROL_TIMEOUT);
		
		sendConfig();
		pingPong();
		send1405AndGetResponse();
		
		// Return to an infinte read timeout after the initial control handshake
		s.setSoTimeout(0);
		
		heartbeatThread = new Thread() {
			@Override
			public void run() {
				while (!isInterrupted())
				{
					try {
						sendHeartbeat();
					} catch (IOException e) {
						listener.connectionTerminated(e);
						return;
					}
					
					
					try {
						Thread.sleep(3000);
					} catch (InterruptedException e) {
						listener.connectionTerminated(e);
						return;
					}
				}
			}
		};
		heartbeatThread.setName("Control - Heartbeat Thread");
		heartbeatThread.start();
		
		resyncThread = new Thread() {
			@Override
			public void run() {
				while (!isInterrupted())
				{
					int[] tuple;
					
					// Wait for a tuple
					try {
						tuple = invalidReferenceFrameTuples.take();
					} catch (InterruptedException e) {
						listener.connectionTerminated(e);
						return;
					}
					
					// Aggregate all lost frames into one range
					int[] lastTuple = null;
					for (;;) {
						int[] nextTuple = lastTuple = invalidReferenceFrameTuples.poll();
						if (nextTuple == null) {
							break;
						}
						
						lastTuple = nextTuple;
					}
					
					// The server expects this to be the firstLostFrame + 1
					tuple[0]++;
					
					// Update the end of the range to the latest tuple
					if (lastTuple != null) {
						tuple[1] = lastTuple[1];
					}
					
					try {
						LimeLog.warning("Invalidating reference frames from "+tuple[0]+" to "+tuple[1]);
						ControlStream.this.sendResync(tuple[0], tuple[1]);
						LimeLog.warning("Frames invalidated");
					} catch (IOException e) {
						listener.connectionTerminated(e);
						return;
					}
				}
			}
		};
		resyncThread.setName("Control - Resync Thread");
		resyncThread.start();
	}
	
	public void startJitterPackets()
	{
		jitterThread = new Thread() {
			@Override
			public void run() {
				while (!isInterrupted())
				{
					try {
						sendJitter();
					} catch (IOException e) {
						listener.connectionTerminated(e);
						return;
					}
					
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						listener.connectionTerminated(e);
						return;
					}
				}
			}
		};
		jitterThread.setName("Control - Jitter Thread");
		jitterThread.start();
	}
	
	private ControlStream.NvCtlResponse send1405AndGetResponse() throws IOException
	{
		return sendAndGetReply(new NvCtlPacket(PTYPE_1405, PPAYLEN_1405));
	}
	
	private void sendResync(int firstLostFrame, int nextSuccessfulFrame) throws IOException
	{
		ByteBuffer conf = ByteBuffer.wrap(new byte[PPAYLEN_RESYNC]).order(ByteOrder.LITTLE_ENDIAN);
		
		//conf.putLong(firstLostFrame);
		//conf.putLong(nextSuccessfulFrame);
		conf.putLong(0);
		conf.putLong(0xFFFFF);
		
		sendAndGetReply(new NvCtlPacket(PTYPE_RESYNC, PPAYLEN_RESYNC, conf.array()));
	}
	
	private void sendConfig() throws IOException
	{
		out.write(config.toWire());
		out.flush();
	}
	
	private void sendHeartbeat() throws IOException
	{
		sendPacket(new NvCtlPacket(PTYPE_HEARTBEAT, PPAYLEN_HEARTBEAT));
	}
	
	private ControlStream.NvCtlResponse pingPong() throws IOException
	{
		sendPacket(new NvCtlPacket(PTYPE_KEEPALIVE, PPAYLEN_KEEPALIVE));
		return new ControlStream.NvCtlResponse(in);
	}
	
	class NvCtlPacket {
		public short type;
		public short paylen;
		public byte[] payload;
		
		public NvCtlPacket(InputStream in) throws IOException
		{
			byte[] header = new byte[4];
			
			int offset = 0;
			do
			{
				int bytesRead = in.read(header, offset, header.length - offset);
				if (bytesRead < 0) {
					break;
				}
				offset += bytesRead;
			} while (offset != header.length);
			
			if (offset != header.length) {
				throw new IOException("Socket closed prematurely");
			}
			
			ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
			
			type = bb.getShort();
			paylen = bb.getShort();
			
			if (paylen != 0)
			{
				payload = new byte[paylen];

				offset = 0;
				do
				{
					int bytesRead = in.read(payload, offset, payload.length - offset);
					if (bytesRead < 0) {
						break;
					}
					offset += bytesRead;
				} while (offset != payload.length);
				
				if (offset != payload.length) {
					throw new IOException("Socket closed prematurely");
				}
			}
		}
		
		public NvCtlPacket(byte[] payload)
		{
			ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
			
			type = bb.getShort();
			paylen = bb.getShort();
			
			if (bb.hasRemaining())
			{
				payload = new byte[bb.remaining()];
				bb.get(payload);
			}
		}
		
		public NvCtlPacket(short type, short paylen)
		{
			this.type = type;
			this.paylen = paylen;
		}
		
		public NvCtlPacket(short type, short paylen, byte[] payload)
		{
			this.type = type;
			this.paylen = paylen;
			this.payload = payload;
		}
		
		public short getType()
		{
			return type;
		}
		
		public short getPaylen()
		{
			return paylen;
		}
		
		public void setType(short type)
		{
			this.type = type;
		}
		
		public void setPaylen(short paylen)
		{
			this.paylen = paylen;
		}
		
		public byte[] toWire()
		{
			ByteBuffer bb = ByteBuffer.allocate(4 + (payload != null ? payload.length : 0)).order(ByteOrder.LITTLE_ENDIAN);
			
			bb.putShort(type);
			bb.putShort(paylen);
			
			if (payload != null)
				bb.put(payload);
			
			return bb.array();
		}
	}
	
	class NvCtlResponse extends NvCtlPacket {
		public short status;
		
		public NvCtlResponse(InputStream in) throws IOException {
			super(in);
		}
		
		public NvCtlResponse(short type, short paylen) {
			super(type, paylen);
		}
		
		public NvCtlResponse(short type, short paylen, byte[] payload) {
			super(type, paylen, payload);
		}
		
		public NvCtlResponse(byte[] payload) {
			super(payload);
		}
		
		public void setStatusCode(short status)
		{
			this.status = status;
		}
		
		public short getStatusCode()
		{
			return status;
		}
	}

	public void connectionTerminated() {
		abort();
	}

	private void resyncConnection(int firstLostFrame, int nextSuccessfulFrame) {
		invalidReferenceFrameTuples.add(new int[]{firstLostFrame, nextSuccessfulFrame});
	}

	public void connectionDetectedFrameLoss(int firstLostFrame, int nextSuccessfulFrame) {
		if (System.currentTimeMillis() > LOSS_PERIOD_MS + lossTimestamp) {
			lossCount++;
			lossTimestamp = System.currentTimeMillis();
		}
		else {
			if (++lossCount == MAX_LOSS_COUNT_IN_PERIOD) {
				listener.displayTransientMessage("Detected excessive A/V data loss. Try improving your network connection or lowering stream resolution and/or frame rate.");
				lossCount = -MAX_LOSS_COUNT_IN_PERIOD * MESSAGE_DELAY_FACTOR;
				lossTimestamp = 0;
			}
		}
		
		resyncConnection(firstLostFrame, nextSuccessfulFrame);
	}

	public void connectionSinkTooSlow(int firstLostFrame, int nextSuccessfulFrame) {
		if (++slowSinkCount == MAX_SLOW_SINK_COUNT) {
			listener.displayTransientMessage("Your device is processing the A/V data too slowly. Try lowering stream resolution and/or frame rate.");
			slowSinkCount = -MAX_SLOW_SINK_COUNT * MESSAGE_DELAY_FACTOR;
		}
		
		resyncConnection(firstLostFrame, nextSuccessfulFrame);
	}
}

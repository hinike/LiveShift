package net.liveshift.video.playbackpolicies;

import java.util.BitSet;

import net.liveshift.configuration.Configuration;
import net.liveshift.core.LiveShiftApplication;
import net.liveshift.download.IncomingBlockRateHandler;
import net.liveshift.signaling.messaging.SegmentBlock;
import net.liveshift.storage.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class RdtPlaybackPolicy implements PlaybackPolicy {

	private static final float MINIMUM_SUBSTREAMS_FOR_PLAYING = .8F;  //0..1
	private static final float MINIMUM_BUFFER_FILL = .8F;  //0..1
	
	final private int numSubstreams;
	final private int initialBufferSizeSeconds;
	private boolean initialBufferDone=false;
	private long startTime = Long.MIN_VALUE;
	final private int guaranteedPlaybackSeconds;
	private int bufferBlocks;
	private int blocksToPerfect;

	private int[] window;  //the window here looks ahead and sees how much we have buffered
	private Integer blocksBuffered = 0;

	private IncomingBlockRateHandler incomingBlockRateHandler;
	private float skipThreshold = .5F;
	
	final private static Logger logger = LoggerFactory.getLogger(PlaybackPolicy.class);

	public RdtPlaybackPolicy(byte numSubstreams, int initialBufferSizeSeconds, int guaranteedPlaybackSeconds) {
		this.numSubstreams=numSubstreams;
		this.initialBufferSizeSeconds=initialBufferSizeSeconds*SegmentBlock.getBlocksPerSecond();
		this.guaranteedPlaybackSeconds = guaranteedPlaybackSeconds;
	}

	@Override
	public void clearWindow(long startTime, long perfectPlayTimeMs) {
		this.startTime = startTime;
		this.blocksToPerfect = (int) (1+(perfectPlayTimeMs-startTime)/Configuration.SEGMENTBLOCK_SIZE_MS);
		
		float incomingBlockRate = this.incomingBlockRateHandler.getIncomingBlockRate();
		int bufferSizeSeconds=(int)(this.guaranteedPlaybackSeconds*(1-incomingBlockRate)*(Configuration.SEGMENTBLOCK_SIZE_MS/1000));
		bufferSizeSeconds = Math.max(this.initialBufferSizeSeconds, bufferSizeSeconds);
		this.bufferBlocks=(int)(bufferSizeSeconds * SegmentBlock.getBlocksPerSecond()*(1/MINIMUM_BUFFER_FILL));
		logger.debug("rate is "+incomingBlockRate+", so I would need to buffer "+ (this.bufferBlocks*MINIMUM_BUFFER_FILL)+"/"+ this.bufferBlocks+" blocks");

		this.window = new int[this.bufferBlocks];
	}

	/**
	 * here you feed your blockmaps that need to be monitored
	 * 
	 * @param segment the segment to use for monitoring
	 */	
	@Override
public void addBlockMap(final Segment segment) {
		
		if (logger.isDebugEnabled()) logger.debug(" addBlockMap("+segment.toString()+")");
		
		if (this.startTime == Long.MIN_VALUE) {
			logger.error("SegmentBlockMonitor must be initialized (reset) before adding block maps");
			LiveShiftApplication.quit("SegmentBlockMonitor must be initialized (reset) before adding block maps");
		}
		
		int startBlockNumber = SegmentBlock.getBlockNumber(this.startTime);
		int blocksPerSegment = SegmentBlock.getBlocksPerSegment();
		
		//gets the part we want from the segmentBlockMap
		BitSet bs = segment.getSegmentBlockMap().get(SegmentBlock.getBlockNumber(Math.max(this.startTime,segment.getSegmentIdentifier().getStartTimeMS())), Math.min(startBlockNumber+this.window.length,blocksPerSegment));
		int shift = Math.max(0, (int) ((segment.getSegmentIdentifier().getStartTimeMS()-this.startTime)/Configuration.SEGMENTBLOCK_SIZE_MS));
		
		//builds window
		int nextBlock = -1;
		synchronized (this.blocksBuffered) {
			
			this.blocksBuffered=0;
			int lastBlock=-1;
			while (nextBlock < this.window.length) {
				
				nextBlock = bs.nextSetBit(nextBlock+1);
				
				if (nextBlock==-1)  //not found || it's the same
					break;
				
				if (nextBlock+shift >= this.window.length)  //past window.length, no sense to go after that
					break;
				
				this.window[nextBlock+shift]++;
				
				if (nextBlock==lastBlock+1 && lastBlock != Integer.MAX_VALUE) {
					this.blocksBuffered++;
					lastBlock=nextBlock;
				}
				else lastBlock=Integer.MAX_VALUE;
			}
		}
	}
	
	@Override
	public PlayingDecision getPlayDecision() {
		int windowPosition=0;
		
		if (this.incomingBlockRateHandler==null) {
			logger.error("you need to specify incomingBlockRateHandler");
			System.exit(1);
		}
		
		//debug
		if (logger.isDebugEnabled()) {
			String windowString = "";
			for (int i=windowPosition;i<this.window.length;i++)
				windowString += " ["+i+"]="+this.window[i];
			logger.debug("window is"+windowString);
		}
		

		int bufferedBlocks=this.getBufferedBlocks();

		//if no window,
		if (initialBufferDone && (this.window.length==0 || bufferedBlocks<1))
//			initialBufferDone=false;  //rebuffers
			return PlayingDecision.STALL;  //stalls

		//initial buffering
		if (!initialBufferDone && bufferedBlocks < bufferBlocks*MINIMUM_BUFFER_FILL) {
 			logger.debug("Buffering "+(bufferBlocks*MINIMUM_BUFFER_FILL)+" of "+bufferBlocks+" blocks"); 			
 			return PlayingDecision.STALL;
		}
		else
			initialBufferDone=true;
		
		//if minimum for playing, play it!
		if (this.window[windowPosition] >= MINIMUM_SUBSTREAMS_FOR_PLAYING * this.numSubstreams)
			return PlayingDecision.PLAY;
		
		//checks threshold
		if ((float)bufferedBlocks/this.window.length < this.skipThreshold ) {
			logger.debug(bufferedBlocks+"/"+this.window.length+"="+((float)bufferedBlocks/this.window.length)+"<"+this.skipThreshold+", so stall");
			return PlayingDecision.STALL;
		}
		else {
			if (this.blocksToPerfect<=1) {
				logger.debug("would skip, but playbackClock would be ahead of perfectPlaybackClock, so stalling");
				return PlayingDecision.STALL;
			}
			else {
				logger.debug(bufferedBlocks+"/"+this.window.length+"="+(bufferedBlocks/this.window.length)+">="+this.skipThreshold+", so skip");
				return PlayingDecision.SKIP;
			}
		}
	}

	private int getBufferedBlocks() {
		return this.getBufferedBlocks(this.window.length);
	}
	
	private int getBufferedBlocks(int limit) {
		int playableBlocks=0;
		for (int i=0;i<this.window.length && playableBlocks<limit;i++) {
			if (this.window[i] >= MINIMUM_SUBSTREAMS_FOR_PLAYING * this.numSubstreams) { 
				playableBlocks++;
			}
		}
		return playableBlocks;
	}
	
	/**
	 * returns how much [0..1] from the next block to be played is available
	 * @return how much [0..1] from the next block to be played is available
	 */
	@Override
	public float shareNextBlock() {
		return (float)this.window[0]/this.numSubstreams;
	}

	@Override
	public int getNumBlocksToSkip() {
		return 1;
	}

	public void registerIncomingBlockRateHandler(IncomingBlockRateHandler incomingBlockRateHandler) {
		this.incomingBlockRateHandler = incomingBlockRateHandler;
	}

	@Override
	public int getBlocksBuffered() {
		synchronized (this.blocksBuffered) {
			return this.blocksBuffered;
		}
	}
}

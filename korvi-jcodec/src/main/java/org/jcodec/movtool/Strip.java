package org.jcodec.movtool;

import static java.lang.System.arraycopy;
import static org.jcodec.common.io.NIOUtils.readableChannel;
import static org.jcodec.common.io.NIOUtils.writableChannel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.RationalLarge;
import org.jcodec.containers.mp4.Chunk;
import org.jcodec.containers.mp4.ChunkReader;
import org.jcodec.containers.mp4.MP4Util;
import org.jcodec.containers.mp4.MP4Util.Movie;
import org.jcodec.containers.mp4.boxes.Box;
import org.jcodec.containers.mp4.boxes.ChunkOffsets64Box;
import org.jcodec.containers.mp4.boxes.ChunkOffsetsBox;
import org.jcodec.containers.mp4.boxes.Edit;
import org.jcodec.containers.mp4.boxes.MediaHeaderBox;
import org.jcodec.containers.mp4.boxes.MovieBox;
import org.jcodec.containers.mp4.boxes.NodeBox;
import org.jcodec.containers.mp4.boxes.SampleSizesBox;
import org.jcodec.containers.mp4.boxes.SampleToChunkBox;
import org.jcodec.containers.mp4.boxes.SampleToChunkBox.SampleToChunkEntry;
import org.jcodec.containers.mp4.boxes.TimeToSampleBox;
import org.jcodec.containers.mp4.boxes.TimeToSampleBox.TimeToSampleEntry;
import org.jcodec.containers.mp4.boxes.TrakBox;
import org.jcodec.platform.Platform;

/**
 * This class is part of JCodec ( www.jcodec.org ) This software is distributed
 * under FreeBSD License
 * 
 * Strips movie to editlist
 * 
 * @author The JCodec project
 * 
 */
public class Strip {
    public static void main1(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Syntax: strip <ref movie> <out movie>");
            System.exit(-1);
        }
        SeekableByteChannel input = null;
        SeekableByteChannel out = null;
        try {
            input = readableChannel(new File(args[0]));
            File file = new File(args[1]);
            Platform.deleteFile(file);
            out = writableChannel(file);
            Movie movie = MP4Util.createRefFullMovie(input, "file://" + new File(args[0]).getAbsolutePath());
            new Strip().strip(movie.getMoov());
            MP4Util.writeFullMovie(out, movie);
        } finally {
            if (input != null)
                input.close();
            if (out != null)
                out.close();
        }
    }

    public void strip(MovieBox movie) throws IOException {
        RationalLarge maxDuration = RationalLarge.ZERO;
        TrakBox[] tracks = movie.getTracks();
        for (int i = 0; i < tracks.length; i++) {
            TrakBox track = tracks[i];
            RationalLarge duration = stripTrack(movie, track);
            if (duration.greaterThen(maxDuration))
                maxDuration = duration;
        }
        movie.setDuration(movie.rescale(maxDuration.getNum(), maxDuration.getDen()));
    }

    public void trim(MovieBox movie, String type) throws IOException {
        RationalLarge maxDuration = RationalLarge.ZERO;
        TrakBox[] tracks = movie.getTracks();
        for (int i = 0; i < tracks.length; i++) {
            TrakBox track = tracks[i];
            RationalLarge duration;
            if (type == null || type.equals(track.getHandlerType())) {
                duration = trimEdits(movie, track);
            } else {
                duration = RationalLarge.R(track.getDuration(), movie.getTimescale());
            }
            if (duration.greaterThen(maxDuration))
                maxDuration = duration;
        }
        movie.setDuration(movie.rescale(maxDuration.getNum(), maxDuration.getDen()));
    }

    public RationalLarge stripTrack(MovieBox movie, TrakBox track) throws IOException {
        ChunkReader chunks = new ChunkReader(track, null);
        List<Edit> edits = track.getEdits();
        List<Edit> oldEdits = deepCopy(edits);
        List<Chunk> result = new ArrayList<Chunk>();

        Chunk chunk;
        while ((chunk = chunks.next()) != null) {
            boolean intersects = false;
            for (Edit edit : oldEdits) {
                if (edit.getMediaTime() == -1)
                    continue; // track offset, not real edit
                long editS = edit.getMediaTime();
                long editE = edit.getMediaTime() + track.rescale(edit.getDuration(), movie.getTimescale());
                long chunkS = chunk.getStartTv();
                long chunkE = chunk.getStartTv() + chunk.getDuration();

                intersects = intersects(editS, editE, chunkS, chunkE);
                if (intersects)
                    break;
            }
            if (!intersects) {
                for (int i = 0; i < oldEdits.size(); i++) {
                    if (oldEdits.get(i).getMediaTime() >= chunk.getStartTv() + chunk.getDuration())
                        edits.get(i).shift(-chunk.getDuration());
                }
            } else
                result.add(chunk);
        }
        NodeBox stbl = NodeBox.findFirstPath(track, NodeBox.class, Box.path("mdia.minf.stbl"));
        stbl.replace("stts", getTimeToSamples(result));
        stbl.replace("stsz", getSampleSizes(result));
        stbl.replace("stsc", getSamplesToChunk(result));
        stbl.removeChildren(new String[] { "stco", "co64" });
        stbl.add(getChunkOffsets(result));
        long duration = totalDuration(result);
        MediaHeaderBox mdhd = NodeBox.findFirstPath(track, MediaHeaderBox.class, Box.path("mdia.mdhd"));
        mdhd.setDuration(duration);
        track.setDuration(movie.rescale(duration, mdhd.getTimescale()));
        return new RationalLarge(duration, mdhd.getTimescale());
    }

    public RationalLarge trimEdits(MovieBox movie, TrakBox track) throws IOException {
        ChunkReader chunks = new ChunkReader(track, null);
        List<Edit> edits = track.getEdits();
        List<Chunk> result = new ArrayList<Chunk>();

        for (Edit edit : edits) {
            long editS = edit.getMediaTime();
            long editE = edit.getMediaTime() + track.rescale(edit.getDuration(), movie.getTimescale());
            
            Chunk chunk = null;
            while ((chunk = chunks.next()) != null) {
                result.add(chunk);
                long chunkS = chunk.getStartTv();
                long chunkE = chunk.getStartTv() + chunk.getDuration();

                if (editS > chunkS) {
                    long cutDur = editS - chunkS;
                    edit.shift(-cutDur);
                    chunk.trimFront(cutDur);
                }
                if (editE < chunkE) {
                    long cutDur = chunkE - editE;
                    edit.stretch(-movie.rescale(cutDur, track.getTimescale()));
                    chunk.trimTail(cutDur);
                }
                if (editE <= chunkE)
                    break;
            }
        }

        NodeBox stbl = NodeBox.findFirstPath(track, NodeBox.class, Box.path("mdia.minf.stbl"));
        stbl.replace("stts", getTimeToSamples(result));
        stbl.replace("stsz", getSampleSizes(result));
        stbl.replace("stsc", getSamplesToChunk(result));
        stbl.removeChildren(new String[] { "stco", "co64" });
        stbl.add(getChunkOffsets(result));
        long duration = totalDuration(result);
        MediaHeaderBox mdhd = NodeBox.findFirstPath(track, MediaHeaderBox.class, Box.path("mdia.mdhd"));
        mdhd.setDuration(duration);
        track.setDuration(movie.rescale(duration, mdhd.getTimescale()));
        return new RationalLarge(duration, mdhd.getTimescale());
    }

    private long totalDuration(List<Chunk> result) {
        long duration = 0;
        for (Chunk chunk : result) {
            duration += chunk.getDuration();
        }
        return duration;
    }

    private List<Edit> deepCopy(List<Edit> edits) {
        ArrayList<Edit> newList = new ArrayList<Edit>();
        for (Edit edit : edits) {
            newList.add(Edit.createEdit(edit));
        }
        return newList;
    }

    public Box getChunkOffsets(List<Chunk> chunks) {
        long[] result = new long[chunks.size()];
        boolean longBox = false;
        int i = 0;
        for (Chunk chunk : chunks) {
            if (chunk.getOffset() >= 0x100000000L)
                longBox = true;
            result[i++] = chunk.getOffset();
        }
        return longBox ? ChunkOffsets64Box.createChunkOffsets64Box(result)
                : ChunkOffsetsBox.createChunkOffsetsBox(result);
    }

    public TimeToSampleBox getTimeToSamples(List<Chunk> chunks) {
        ArrayList<TimeToSampleEntry> tts = new ArrayList<TimeToSampleEntry>();
        int curTts = -1, cnt = 0;
        for (Chunk chunk : chunks) {
            if (chunk.getSampleDur() != Chunk.UNEQUAL_DUR) {
                if (curTts == -1 || curTts != chunk.getSampleDur()) {
                    if (curTts != -1)
                        tts.add(new TimeToSampleEntry(cnt, curTts));
                    cnt = 0;
                    curTts = chunk.getSampleDur();
                }
                cnt += chunk.getSampleCount();
            } else {
                for (int dur : chunk.getSampleDurs()) {
                    if (curTts == -1 || curTts != dur) {
                        if (curTts != -1)
                            tts.add(new TimeToSampleEntry(cnt, curTts));
                        cnt = 0;
                        curTts = dur;
                    }
                    ++cnt;
                }
            }
        }
        if (cnt > 0)
            tts.add(new TimeToSampleEntry(cnt, curTts));
        return TimeToSampleBox.createTimeToSampleBox(tts.toArray(new TimeToSampleEntry[0]));
    }

    public SampleSizesBox getSampleSizes(List<Chunk> chunks) {
        int nSamples = 0;
        int prevSize = chunks.size() != 0 ? chunks.get(0).getSampleSize() : 0;
        for (Chunk chunk : chunks) {
            nSamples += chunk.getSampleCount();
            if (prevSize == 0 && chunk.getSampleSize() != 0)
                throw new RuntimeException("Mixed sample sizes not supported");
        }

        if (prevSize > 0)
            return SampleSizesBox.createSampleSizesBox(prevSize, nSamples);

        int[] sizes = new int[nSamples];
        int startSample = 0;
        for (Chunk chunk : chunks) {
            arraycopy(chunk.getSampleSizes(), 0, sizes, startSample, chunk.getSampleCount());
            startSample += chunk.getSampleCount();
        }
        return SampleSizesBox.createSampleSizesBox2(sizes);
    }

    public SampleToChunkBox getSamplesToChunk(List<Chunk> chunks) {
        ArrayList<SampleToChunkEntry> result = new ArrayList<SampleToChunkEntry>();
        Iterator<Chunk> it = chunks.iterator();
        if (it.hasNext()) {
            Chunk chunk = it.next();
            int curSz = chunk.getSampleCount();
            int curEntry = chunk.getEntry();
            int first = 1, cnt = 1;
            while (it.hasNext()) {
                chunk = it.next();
                int newSz = chunk.getSampleCount();
                int newEntry = chunk.getEntry();
                if (curSz != newSz || curEntry != newEntry) {
                    result.add(new SampleToChunkEntry(first, curSz, curEntry));
                    curSz = newSz;
                    curEntry = newEntry;
                    first += cnt;
                    cnt = 0;
                }
                ++cnt;
            }
            result.add(new SampleToChunkEntry(first, curSz, curEntry));
        }

        return SampleToChunkBox.createSampleToChunkBox(result.toArray(new SampleToChunkEntry[0]));
    }

    private boolean intersects(long a, long b, long c, long d) {
        return (a >= c && a < d) || (b >= c && b < d) || (c >= a && c < b) || (d >= a && d < b);
    }
}
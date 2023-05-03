package edu.mcw.rgd.dataload.ontologies;

import org.apache.logging.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

public class FileSystemLock implements AutoCloseable {

    private String _fileName = "/tmp/TermStatsLoader.lock";
    private RandomAccessFile _file = new RandomAccessFile(_fileName, "rw");
    private FileChannel _channel;
    private FileLock _lock;

    private int maxAttempts = 12;
    private long sleepIntervalInMs = 1000*60*10; // 10 min

    public FileSystemLock( int maxAttempts, long sleepIntervalInMs ) throws FileNotFoundException {
        this.maxAttempts = maxAttempts;
        this.sleepIntervalInMs = sleepIntervalInMs;
    }

    public synchronized void acquire( Logger log ) throws IOException, InterruptedException {
        _channel = _file.getChannel();

        do {
            _lock = _channel.tryLock();
            if( _lock==null ) {
                log.warn(" *** cannot acquire lock to "+_fileName+";  sleeping 10 min");
                Thread.sleep(1000*60*10);
            }
        } while( _lock == null );
        log.info(" *** acquired lock to "+_fileName);
    }

    public synchronized void release( Logger log ) throws IOException {
        if( _lock!=null ) {
            _lock.release();
            _lock = null;
            log.info(" *** released lock to "+_fileName);
        }
    }

    @Override
    public void close() throws Exception {
        if( _file!=null ) {
            _file.close();
            _file = null;
        }
    }
}

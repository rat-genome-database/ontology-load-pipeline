package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.process.Utils;
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

    private String name = "";
    private int maxAttempts = 12;
    private long sleepIntervalInMs = 1000*60*10; // 10 min

    public FileSystemLock( int maxAttempts, long sleepIntervalInMs, String name ) throws FileNotFoundException {
        this.maxAttempts = maxAttempts;
        this.sleepIntervalInMs = sleepIntervalInMs;
        this.name = name;
    }

    public synchronized void acquire( Logger log ) throws IOException, InterruptedException {
        _channel = _file.getChannel();

        int attempt;
        for( attempt=0; attempt<maxAttempts; attempt++ ) {
            _lock = _channel.tryLock();
            if( _lock==null ) {
                log.debug(" *** "+name+" *** cannot acquire lock to "+_fileName+";  sleeping "+ Utils.formatElapsedTime(0, sleepIntervalInMs)+"; attempt = "+attempt);
                Thread.sleep(sleepIntervalInMs);
            } else {
                log.info(" *** "+name+" *** acquired lock to "+_fileName+"; attempt = "+attempt);
                break;
            }
        }
    }

    public synchronized void release( Logger log ) throws IOException {
        if( _lock!=null ) {
            _lock.release();
            _lock = null;
            log.info(" *** "+name+" *** released lock to "+_fileName);
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

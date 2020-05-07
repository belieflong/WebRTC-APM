package com.example.sino.apm_test;

import android.app.AlertDialog;
import android.content.Context;
import android.databinding.DataBindingUtil;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Process;
import android.support.v7.app.AppCompatActivity;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import android.widget.ScrollView;


import com.example.sino.apm_test.databinding.ActivityMainBinding;
import com.example.soundtouchdemo.JNISoundTouch;
import com.sinowave.ddp.Apm;
import com.sinowave.ddp.SyncQueue;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MainActivity extends AppCompatActivity {
    private int loggerPrint; //用于打印

    private static final int CHANNELS = 1;                                                   //通道
    private static final int BITS_PER_SAMPLE = 16;                                           //位深
    private static final int SAMPLE_RATE = 16000;                                            //采样率


    private static final int CALLBACK_BUFFER_SIZE_MS = 10;                                   //回调buffer大小毫秒[向客户端提供的每个记录缓冲区的请求大小]
    private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;            //100(buffer每秒 = 1000 / 回调buffer大小毫秒)[每秒回调的平均次数]

    private static final int AEC_BUFFER_SIZE_MS = 10;                                        //AEC buffer大小毫秒
    private static final int AEC_LOOP_COUNT = CALLBACK_BUFFER_SIZE_MS / AEC_BUFFER_SIZE_MS;  //AEC循环数[10/10] (回调buffer大小毫秒 / AEC buffer大小毫秒)

    private static final int JITTER_STEP_SIZE = CHANNELS * SAMPLE_RATE / BUFFERS_PER_SECOND; //抖动大小160

    private Apm _apm ;

    private ApmViewModel vm;

    private RecordThread _recordThread;

    private RecvThread _recvThread;
    private TrackThread _trackThread;

    private final int buffer_count = 15;
    SyncQueue<short[]> _receveQueue = new SyncQueue<short[]>(buffer_count) ;

    private final int PORT = 13000;

    DatagramSocket _dataSocket;

    int _receveCount = 0;
    int _sendCount = 0;

    int _aecPCLevel = 2;     //默认高水平抑制
    int _aecMobileLevel = 3; //默认为喇叭扩音器
    int _nsLevel = 2;        //默认高降噪
    int _agcLevel = 0;       //默认模拟自适应

    Handler handler = new Handler();
    Runnable run = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, 1000);
            vm.setRcvCount(_receveCount);
            vm.setSndCount(_sendCount);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        setContentView(R.layout.activity_main);
        ActivityMainBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        vm = new ApmViewModel(this);
        binding.setApm(vm);


        ScrollView view = (ScrollView)findViewById(R.id.scrollView);
        view.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                v.requestFocusFromTouch();
                return false;
            }
        });


        for(int i = 0 ; i < buffer_count; ++i){
            short[] a = new short[JITTER_STEP_SIZE];
            try {
                _receveQueue.Consumer_Put(a);
            }catch (InterruptedException e){}
        }

        handler.postDelayed(run, 1000);
    }

    void getLevels(){
        if (vm.getAecPCMode0()){
            _aecPCLevel = 0;
        }else if(vm.getAecPCMode1()){
            _aecPCLevel = 1;
        }else if(vm.getAecPCMode2()){
            _aecPCLevel = 2;
        }

        if (vm.getAecMobileMode0()){
            _aecMobileLevel = 0;
        }else if(vm.getAecMobileMode1()){
            _aecMobileLevel = 1;
        }else if(vm.getAecMobileMode2()){
            _aecMobileLevel = 2;
        }else if(vm.getAecMobileMode3()){
            _aecMobileLevel = 3;
        }else if(vm.getAecMobileMode4()){
            _aecMobileLevel = 4;
        }

        if(vm.getNsMode0()){
            _nsLevel = 0;
        }else if(vm.getNsMode1()){
            _nsLevel = 1;
        }else if(vm.getNsMode2()){
            _nsLevel = 2;
        }else if(vm.getNsMode3()){
            _nsLevel = 3;
        }

        if(vm.getAgcMode0()){
            _agcLevel = 0;
        }else if(vm.getAgcMode1()){
            _agcLevel = 1;
        }else if(vm.getAgcMode2()){
            _agcLevel = 2;
        }
    }

    void Start(){
        if (_trackThread == null || _recordThread == null) {
            try {
                InetAddress localAddr = InetAddress.getByName("0.0.0.0");
                _dataSocket = new DatagramSocket(PORT, localAddr);
                _dataSocket.setReuseAddress(true);

                _dataSocket.setSendBufferSize(2 * 1024 * 1024);
                _dataSocket.setReceiveBufferSize(2 * 1024 * 1024);

            } catch (SocketException ex) {
                new AlertDialog.Builder(MainActivity.this).setTitle("系统提示")
                        .setMessage(ex.getMessage())
                        .show();
                return;
            } catch (UnknownHostException ex) {
                new AlertDialog.Builder(MainActivity.this).setTitle("系统提示")
                        .setMessage(ex.getMessage())
                        .show();
                return;
            } catch (Exception ex) {
                new AlertDialog.Builder(MainActivity.this).setTitle("系统提示")
                        .setMessage("网络错误" + ex.getMessage())
                        .show();
                return;
            }

            getLevels();

            int ret = -1;
            try {

                _apm = new Apm( vm.getAecExtendFilter(), vm.getSpeechIntelligibilityEnhance(), vm.getDelayAgnostic(), vm.getBeamForming(),
                        vm.getNextGenerationAEC(), vm.getExperimentalNS(), vm.getExperimentalAGC());

                ret = _apm.HighPassFilter(vm.getHighPassFilter());

                if (vm.getAecPC()) {
                    ret = _apm.AECClockDriftCompensation(false);
                    ret = _apm.AECSetSuppressionLevel(Apm.AEC_SuppressionLevel.values()[_aecPCLevel]);
                    ret = _apm.AEC(true);
                    Logger.e("vm.getAecPC() " + " _aecPCLevel = " + _aecPCLevel);
                }else if (vm.getAecMobile()) {
                    ret = _apm.AECMSetSuppressionLevel(Apm.AECM_RoutingMode.values()[_aecMobileLevel]);
                    ret = _apm.AECM(true);
                    Logger.e("vm.getAecMobile() " + " _aecMobileLevel = " + _aecMobileLevel);
                }


                ret = _apm.NSSetLevel(Apm.NS_Level.values()[_nsLevel]);
                ret = _apm.NS(vm.getNs());

                ret = _apm.VAD(vm.getVad());

                if (vm.getAgc()) {
                    ret = _apm.AGCSetAnalogLevelLimits(0, 255);
                    ret = _apm.AGCSetMode(Apm.AGC_Mode.values()[_agcLevel]);
                    ret = _apm.AGCSetTargetLevelDbfs(vm.getTargetLevelInt());
                    ret = _apm.AGCSetcompressionGainDb(vm.getCompressionGainInt());
                    ret = _apm.AGCEnableLimiter(true);
                    ret = _apm.AGC(true);
                }
            } catch (Exception ex) {
                new AlertDialog.Builder(MainActivity.this).setTitle("系统提示")
                        .setMessage(ex.getMessage())
                        .show();
                return;
            }

            _recvThread = new RecvThread();
            _recvThread.Start();

            _trackThread = new TrackThread();
            _trackThread.Start();

            _recordThread = new RecordThread();
            _recordThread.Start();

            vm.setStart(true);

        } else {
            vm.setStart(false);

            _dataSocket.close();

            _recvThread.Stop();
            _recordThread.Stop();
            _trackThread.Stop();
            _dataSocket = null;
            _recvThread = null;
            _recordThread = null;
            _trackThread = null;

            _apm.close();

            _receveCount = 0;
            _sendCount = 0;

            try {
                if (dos != null){
                    dos.close();
                }
            } catch (IOException e) {
                Logger.e("关闭流失败");
            }
        }
    }

    public void onSpeaker(boolean on){
        Context context = getApplicationContext();
        AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(on);
//        boolean b = audioManager.isVolumeFixed();
//        boolean c = b;
    }

    @Override
    protected void onPause(){
        super.onPause();


    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        vm.setStart(false);
        if(_dataSocket != null){
            _dataSocket.close();
            _dataSocket = null;
        }
        if(_recvThread != null) {
            _recvThread.Stop();
            _recvThread = null;
        }
        if(_recordThread != null) {
            _recordThread.Stop();
            _recordThread = null;
        }
        if(_trackThread != null) {
            _trackThread.Stop();
            _trackThread = null;
        }
        if(_apm != null) {
            _apm.close();
        }

        _receveCount = 0;
        _sendCount = 0;

        if(handler != null) {
            handler.removeCallbacks(run);
        }
    }

    private void ShowMessage(String msg) {
        final String message = msg;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(MainActivity.this).setTitle("系统提示")
                        .setMessage(message)
                        .show();
            }
        });
    }


    private class RecordThread extends Thread {

        private boolean _done = false;
        private AudioRecord _audioRecord = null;

        private static final int CHANNELS = 1;

        // Default audio data format is PCM 16 bit per sample.(默认的音频数据格式是PCM 16位每个样本)
        // Guaranteed to be supported by all devices.
        private static final int BITS_PER_SAMPLE = 16;

        private static final int SAMPLE_RATE = 16000;

        // Requested size of each recorded buffer provided to the client.

        // Average number of callbacks per second.
        private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;

        // We ask for a native buffer size of BUFFER_SIZE_FACTOR * (minimum required
        // buffer size). The extra space is allocated to guard against glitches under
        // high load.
        private static final int BUFFER_SIZE_FACTOR = 2;

        public void Start() {
            _done = false;
            this.start();
        }

        public void Stop() {
            _done = true;
            try {
                this.join();
            } catch (InterruptedException ex) {}
        }

        @Override
        public void run() {
            super.run();

            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

            final int bytesPerFrame = CHANNELS * (BITS_PER_SAMPLE / 8); //每帧字节
            final int framesPerBuffer = SAMPLE_RATE / BUFFERS_PER_SECOND; //1600 / (1000 / 10) -> 16 //每个缓冲区多少帧

            int minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            // Use a larger buffer size than the minimum required when creating the
            // AudioRecord instance to ensure smooth recording under load. It has been
            // verified that it does not increase the actual recording latency.

            int bufferSizeInBytes =
                    Math.max(BUFFER_SIZE_FACTOR * minBufferSize, 0);

            short[] processBuffer = new short[bytesPerFrame * framesPerBuffer/2];
            byte[] sendBuffer = new byte[bytesPerFrame * framesPerBuffer];

            DatagramPacket dataPacket;
            try {
                InetAddress dstAddress = InetAddress.getByName(vm.getTargetIP());
                dataPacket = new DatagramPacket(sendBuffer, 0, sendBuffer.length, dstAddress, PORT);

                int audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
                _audioRecord = new AudioRecord(audioSource,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSizeInBytes);

                if(AutomaticGainControl.isAvailable()){
                    AutomaticGainControl agc = AutomaticGainControl.create(
                            _audioRecord.getAudioSessionId()
                    );
                    agc.setEnabled(false);
                }

/*
                Context context = getApplicationContext();
                AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                int index = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 30, 0);//*/

                _audioRecord.startRecording();

            } catch (IllegalArgumentException ex) {
                ShowMessage(ex.getMessage());
                Log.e("record", ex.getMessage());
                return;
            } catch (Exception ex) {
                ShowMessage(ex.getMessage());
                Log.e("record", ex.getMessage());
                return;
            }

            int out_analog_level = 200;
            try {
                while (!_done) {

                    int bytesRead = _audioRecord.read(processBuffer, 0, processBuffer.length);
                    if (bytesRead == processBuffer.length) {
                        for(int i = 0 ; i < AEC_LOOP_COUNT; ++i) {
                            int processBufferOffSet = i * processBuffer.length / AEC_LOOP_COUNT;

                            _apm.SetStreamDelay(vm.getBufferDelayMs()); //设置流的延迟
                            if (vm.getAgc()) {
                                _apm.AGCSetStreamAnalogLevel(out_analog_level); //首次默认值为200,设置流的AGC模拟级别
                            }

                            _apm.ProcessCaptureStream(processBuffer, processBufferOffSet); //本地数据 音频前处理

                            if (vm.getAgc()) {
                                out_analog_level = _apm.AGCStreamAnalogLevel(); //之后实时更新流的AGC模拟级别
//                                Log.i("AGC", out_analog_level + "");
                            }

                            if (vm.getVad()) {
                                if (!_apm.VADHasVoice()) continue;
                            }
                        }

                        /*
                        final int USHORT_MASK = (1 << 16) - 1;
                        for(int j = 0 ; j < processBuffer.length; ++j) {
                            int sample = (int) processBuffer[j] & USHORT_MASK;
                            sample *= 5.0f;
                            processBuffer[j] = (short)(sample & USHORT_MASK);
                        }
                        //*/

                        //音频前处理后，写出来的数据为 16 大 1 16000
                        //writePCM(processBuffer); //01_写入录制数据

                        ByteBuffer.wrap(sendBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(processBuffer); //short数组再转换回byte数组(同时设置byte数组->顺序为小端存储)

                        //转换成byte(小端)后，写出来的数据为32 小 1 16000


                        _dataSocket.send(dataPacket);
                        _sendCount++;

                        if (loggerPrint++%500 == 0){
                            Logger.e("processBuffer.length = " + processBuffer.length + " bytesRead = " + bytesRead + " vm.getBufferDelayMs() = " + vm.getBufferDelayMs());
                            Logger.e("AEC_LOOP_COUNT = " + AEC_LOOP_COUNT);
                            loggerPrint = 1;
                        }

                    } else {
                        Log.e("record", "AudioRecord.read failed: " + bytesRead);
                    }
                }
            } catch (IOException ex) {
                if (vm.getStart()) ShowMessage(ex.getMessage());
            } catch (Exception ex) {
                if (vm.getStart()) ShowMessage(ex.getMessage());
            }
            _audioRecord.stop();
            _audioRecord.release();
        }
    }

    private class RecvThread extends Thread{

        private boolean _done = false;
        public void Start() {
            _done = false;
            this.start();
        }

        public void Stop() {
            _done = true;
            try {
                this.join();
            } catch (InterruptedException ex) {

            }
        }

        @Override
        public void run() {
            super.run();

            final int bytesPerFrame = CHANNELS * (BITS_PER_SAMPLE / 8);
            byte[] recvBuffer = new byte[bytesPerFrame * (SAMPLE_RATE / BUFFERS_PER_SECOND)];
            DatagramPacket dataPacket = new DatagramPacket(recvBuffer, 0, recvBuffer.length);

            try {
                while (!_done) {
                    _dataSocket.receive(dataPacket);
                    _receveCount++;

                    short[] buffer = _receveQueue.Producer_Get(150L);
                    if( buffer != null) {
                        ByteBuffer.wrap(recvBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buffer);
                        _receveQueue.Producer_Put(buffer);
                    }
                }
            }catch (IOException ex) {
                if (vm.getStart()) ShowMessage(ex.getMessage());
                Log.e("recv", ex.getMessage());
            }catch (Exception ex){
                if (vm.getStart()) ShowMessage(ex.getMessage());
                Log.e("recv", ex.getMessage());
            }
        }
    }


    private class TrackThread extends Thread {

        class PlayedSamples{

            public void setPlayedSamples(int samples){
                long current = getUnsignedInt(samples);
                if( current < _low ){
                    _high += 0x00000000ffffffffL;
                }
                _low = current;
            }
            public long getPlayedSamples(){
                return _high + _low;
            }

            private long getUnsignedInt (int data){
                return data & 0x00000000ffffffffL;
            }

            private long _low = 0;
            private long _high = 0;
        }

        private JNISoundTouch soundtouch = new JNISoundTouch();

        private boolean _done = false;

        private AudioTrack _audioTrack = null;

        public void Start() {
            _done = false;
            this.start();
        }

        public void Stop() {
            _done = true;
            try {
                this.join();
            } catch (InterruptedException ex) {

            }
        }

        public long getUnsignedInt (int data){
            return data & 0x00000000ffffffffL;
        }

        public int getUnsignedShortInt (short data){
            return data & 0x0000ffff;
        }

        @Override
        public void run() {
            super.run();

            soundtouch.setSampleRate(16000);
            soundtouch.setChannels(1);

            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

            final int bytesPerFrame = CHANNELS * (BITS_PER_SAMPLE / 8);
            final int minBufferSizeInBytes = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            try {

                // Create an AudioTrack object and initialize its associated audio buffer.
                // The size of this buffer determines how long an AudioTrack can play
                // before running out of data.
                _audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSizeInBytes,
                        AudioTrack.MODE_STREAM);

                _audioTrack.play();

            } catch (IllegalArgumentException ex) {
                ShowMessage(ex.getMessage());
                Log.e("track", ex.getMessage());
                return;
            } catch (Exception ex) {
                ShowMessage(ex.getMessage());
            }

            long writtenSamples = 0;
            PlayedSamples playedSamples = new PlayedSamples();

            try {
                while (!_done) {

                    short[] buffer = _receveQueue.Consumer_Get(150L);
                    if(buffer != null){
                        for (int i = 0; i < AEC_LOOP_COUNT; ++i) {
                            int bufferOffSet = i * buffer.length / AEC_LOOP_COUNT;
                            if (!vm.getAecNone()) {
                                _apm.ProcessRenderStream(buffer, bufferOffSet);
                            }
                        }

                        int size = _receveQueue.UsedSize();
                        if(size >= 5 /*|| (writtenSamples - playedSamples.getPlayedSamples() >= 800)*/) {
//                            Log.d("soundtouch", "process " + size);
                            soundtouch.setTempoChange(20);
                            soundtouch.putSamples(buffer, buffer.length);

                            short[] data;
                            do {

                                data = soundtouch.receiveSamples();
                                if (data.length <= 0) break;

//                                Log.i("track", writtenSamples - playedSamples.getPlayedSamples() + "");
                                playedSamples.setPlayedSamples(_audioTrack.getPlaybackHeadPosition());
                                //writePCM(data); //01_写入接收数据(16 大 1 16000)
                                int samplesWritten = _audioTrack.write(data, 0, data.length);
                                if (samplesWritten != data.length) {
                                    _done = true;
                                }
                                writtenSamples += samplesWritten;

                            } while (true);
                        }else {
//                            Log.i("track", writtenSamples - playedSamples.getPlayedSamples() + "");
                            playedSamples.setPlayedSamples(_audioTrack.getPlaybackHeadPosition());
                            //writePCM(buffer); //02_写入接收数据(16 大 1 16000)
                            int samplesWritten = _audioTrack.write(buffer, 0, buffer.length);
                            if (samplesWritten != buffer.length) {
                                _done = true;
                            }
                            writtenSamples += samplesWritten;
                        }
                        _receveQueue.Consumer_Put(buffer);
                    }
                }
            } catch (Exception ex) {
                if (vm.getStart()) ShowMessage(ex.getMessage());
                Log.e("track", ex.getMessage());
            }
            _audioTrack.stop();
            _audioTrack.release();
        }
    }

    /**
     * 本地写入音频数据
     */
    private File pcmFile = null;
    private DataOutputStream dos;
    private void writePCM(short[] buffer){
        try {
            if (pcmFile == null){
                pcmFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/reverseme.pcm");
                if (pcmFile.exists()){
                    pcmFile.delete();
                    Logger.e("删除本地保存的PCM文件");
                }
                pcmFile.createNewFile();
                Logger.e("创建本地保存的PCM文件");
            }
        }catch (IOException e){
            Logger.e("未能创建");
            throw new IllegalStateException("未能创建" + pcmFile.toString());
        }

        try{
            if (dos == null){
                OutputStream os = new FileOutputStream(pcmFile);
                BufferedOutputStream bos = new BufferedOutputStream(os);
                dos = new DataOutputStream(bos);
            }
            if (dos != null){
                for (int i = 0; i < buffer.length; i++){
                    dos.writeShort(buffer[i]);
                }
            }
        }catch (Exception e){
            Logger.e("写入文件失败");
        }
    }
}

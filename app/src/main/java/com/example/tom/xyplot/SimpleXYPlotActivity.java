package com.example.tom.xyplot;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.androidplot.Plot;
import com.androidplot.util.PixelUtils;
import com.androidplot.xy.XYSeries;
import com.androidplot.xy.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.Observable;
import java.util.Observer;
import java.util.UUID;

public class SimpleXYPlotActivity extends Activity {

    // redraws a plot whenever an update is received:
    private class MyPlotUpdater implements Observer {
        Plot plot;

        public MyPlotUpdater(Plot plot) {
            this.plot = plot;
        }

        @Override
        public void update(Observable o, Object arg) {
            plot.redraw();
        }
    }

    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static String address = "20:16:03:08:53:65";
    private XYPlot dynamicPlot;
    private MyPlotUpdater plotUpdater;
    SampleDynamicXYDatasource data;
    private Thread myThread;
    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    //private ConnectedThread mConnectedThread;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Set up a pointer to the remote node using it's address.
        BluetoothDevice device = btAdapter.getRemoteDevice(address);

        // Two things are needed to make a connection:
        //   A MAC address, which we got above.
        //   A Service ID or UUID.  In this case we are using the
        //     UUID for SPP.

        try {
            btSocket = createBluetoothSocket(device);
        } catch (IOException e) {
            errorExit("Fatal Error", "In onResume() and socket create failed: " + e.getMessage() + ".");
        }

        // Discovery is resource intensive.  Make sure it isn't going on
        // when you attempt to connect and pass your message.
        btAdapter.cancelDiscovery();

        // Establish the connection.  This will block until it connects.
        try {
            btSocket.connect();
        } catch (IOException e) {
            try {
                btSocket.close();
            } catch (IOException e2) {
                errorExit("Fatal Error", "In onResume() and unable to close socket during connection failure" + e2.getMessage() + ".");
            }
        }

        // Create a data stream so we can talk to server.


        // android boilerplate stuff

        setContentView(R.layout.dynamic_xyplot_example);

        // get handles to our View defined in layout.xml:
        dynamicPlot = findViewById(R.id.dynamicXYPlot);

        plotUpdater = new MyPlotUpdater(dynamicPlot);

        // only display whole numbers in domain labels
        dynamicPlot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.BOTTOM).
                setFormat(new DecimalFormat("0"));

        // getInstance and position datasets:
        data = new SampleDynamicXYDatasource(btSocket);
        SampleDynamicSeries sine1Series = new SampleDynamicSeries(data, 0, "Sine 1");

        LineAndPointFormatter formatter1 = new LineAndPointFormatter(
                Color.rgb(0, 200, 0), null, null, null);
        formatter1.getLinePaint().setStrokeJoin(Paint.Join.ROUND);
        formatter1.getLinePaint().setStrokeWidth(10);
        dynamicPlot.addSeries(sine1Series,
                formatter1);



        // hook up the plotUpdater to the data model:
        data.addObserver(plotUpdater);

        // thin out domain tick labels so they dont overlap each other:
        dynamicPlot.setDomainStepMode(StepMode.INCREMENT_BY_VAL);
        dynamicPlot.setDomainStepValue(5);

        dynamicPlot.setRangeStepMode(StepMode.INCREMENT_BY_VAL);
        dynamicPlot.setRangeStepValue(10);

        dynamicPlot.getGraph().getLineLabelStyle(
                XYGraphWidget.Edge.LEFT).setFormat(new DecimalFormat("###.#"));

        // uncomment this line to freeze the range boundaries:
        dynamicPlot.setRangeBoundaries(-100, 100, BoundaryMode.FIXED);

        // create a dash effect for domain and range grid lines:
        DashPathEffect dashFx = new DashPathEffect(
                new float[] {PixelUtils.dpToPix(3), PixelUtils.dpToPix(3)}, 0);
        dynamicPlot.getGraph().getDomainGridLinePaint().setPathEffect(dashFx);
        dynamicPlot.getGraph().getRangeGridLinePaint().setPathEffect(dashFx);


    }

    @Override
    public void onResume() {
        // kick off the data generating thread:
        myThread = new Thread(data);
        myThread.start();
        super.onResume();

    }

    private void errorExit(String title, String message){
        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
        finish();
    }

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        try {
            final Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", new Class[] { UUID.class });
            return (BluetoothSocket) m.invoke(device, MY_UUID);
        } catch (Exception e) {

        }

        return  device.createRfcommSocketToServiceRecord(MY_UUID);
    }

    @Override
    public void onPause() {
        data.stopThread();
        super.onPause();
        try     {
            btSocket.close();
        } catch (IOException e2) {
            errorExit("Fatal Error", "In onPause() and failed to close socket." + e2.getMessage() + ".");
        }
    }

    class SampleDynamicXYDatasource implements Runnable {

        private final InputStream mmInStream;

        public SampleDynamicXYDatasource(BluetoothSocket socket){
            InputStream tmpIn = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
            } catch (IOException e) {errorExit("Fatal Error", "In onPause() and failed to close socket." + e.getMessage() + "."); }

            mmInStream = tmpIn;
        }

        // encapsulates management of the observers watching this datasource for update events:
        class MyObservable extends Observable {
            @Override
            public void notifyObservers() {
                setChanged();
                super.notifyObservers();
            }
        }

        private static final int SAMPLE_SIZE = 31;
        private MyObservable notifier;
        private boolean keepRunning = false;
        private byte[] buffer = new byte[256];  // buffer store for the stream
        private float inByte = 0;
        private float height = 0, height_new = 0;

        {
            notifier = new MyObservable();
        }

        public void stopThread() {
            keepRunning = false;
        }

        @Override
        public void run() {
            try {
                keepRunning = true;
                int bytes; // bytes returned from read()

                // Keep listening to the InputStream until an exception occurs
                while (keepRunning) {
                    try {
                        // Read from the InputStream
                        bytes = mmInStream.read(buffer);        // Get number of bytes and message in "buffer"
                        String str = new String(buffer, StandardCharsets.UTF_8);
                        String inString = str.trim();
                        inByte = Float.parseFloat(inString);
                        /*if (inString.equals("!"))
                            inByte = 512;
                        else
                            inByte = Float.parseFloat(inString);
                        inByte = map(inByte,0, 1023, 0, height);
                        height_new = height - inByte;*/

                    } catch (IOException e) {
                        break;
                    }
                    Thread.sleep(10); // decrease or remove to speed up the refresh rate.
                    notifier.notifyObservers();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public int getItemCount(int series) {
            return SAMPLE_SIZE;
        }

        public Number getX(int series, int index) {
            if (index >= SAMPLE_SIZE) {
                throw new IllegalArgumentException();
            }
            return index;
        }

        public Number getY(int series, int index) {
            float screenY = inByte;
            XYPlot plot = null;
            Number y = plot.screenToSeriesY(screenY);
            return y;
        }

        /* public final float map(float value,
                                      float istart,
                                      float istop,
                                      float ostart,
                                      float ostop) {
            return ostart + (ostop - ostart) * ((value - istart) / (istop - istart));
        }*/

        public void addObserver(Observer observer) {
            notifier.addObserver(observer);
        }

        public void removeObserver(Observer observer) {
            notifier.deleteObserver(observer);
        }

    }

    class SampleDynamicSeries implements XYSeries {
        private SampleDynamicXYDatasource datasource;
        private int seriesIndex;
        private String title;


        public SampleDynamicSeries(SampleDynamicXYDatasource datasource, int seriesIndex, String title) {
            this.datasource = datasource;
            this.seriesIndex = seriesIndex;
            this.title = title;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public int size() {
            return datasource.getItemCount(seriesIndex);
        }

        @Override
        public Number getX(int index) {
            return datasource.getX(seriesIndex, index);
        }

        @Override
        public Number getY(int index) {
            return datasource.getY(seriesIndex, index);
        }
    }

    /*private class ConnectedThread extends Thread {
        private final InputStream mmInStream;

        public ConnectedThread(BluetoothSocket socket) {
            InputStream tmpIn = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
            } catch (IOException e) { }

            mmInStream = tmpIn;
        }

        public void run() {
            byte[] buffer = new byte[256];  // buffer store for the stream
            int bytes; // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);        // Get number of bytes and message in "buffer"
                } catch (IOException e) {
                    break;
                }
            }
        }
    }*/
}
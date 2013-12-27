/* Copyright 2011 Google Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: http://code.google.com/p/usb-serial-for-android/
 */

package com.hoho.android.usbserial.examples;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.view.View;
import android.os.SystemClock;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.util.HexDump;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Arrays;

/**
 * Monitors a single {@link UsbSerialDriver} instance, showing all data
 * received.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public class SerialConsoleActivity extends Activity {

    private final String TAG = SerialConsoleActivity.class.getSimpleName();

    /**
     * Driver instance, passed in statically via
     * {@link #show(Context, UsbSerialDriver)}.
     *
     * <p/>
     * This is a devious hack; it'd be cleaner to re-create the driver using
     * arguments passed in with the {@link #startActivity(Intent)} intent. We
     * can get away with it because both activities will run in the same
     * process, and this is a simple demo.
     */
    private static UsbSerialDriver sDriver = null;

    private TextView mTitleTextView;
    private TextView mDumpTextView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.serial_console);
        mTitleTextView = (TextView) findViewById(R.id.demoTitle);
        mDumpTextView = (TextView) findViewById(R.id.show_message);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sDriver != null) {
            try {
                sDriver.close();
            } catch (IOException e) {
                // Ignore.
            }
            sDriver = null;
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Resumed, sDriver=" + sDriver);
        if (sDriver == null) {
            mTitleTextView.setText("No serial device.");
        } else {
            try {
                sDriver.open();
                sDriver.setParameters(115200, 8, UsbSerialDriver.STOPBITS_1, UsbSerialDriver.PARITY_NONE);
            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                mTitleTextView.setText("Error opening device: " + e.getMessage());
                try {
                    sDriver.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sDriver = null;
                return;
            }
            mTitleTextView.setText("Serial device: " + sDriver.getClass().getSimpleName());

            try {
		if(send_wait_ack(TCOM_DOSERATE)!=0){
		    mDumpTextView.append("can't send doserate command\n");
		    return;
		}
		if(send_wait_ack(TCOM_MES30SEC)!=0){
		    mDumpTextView.append("can't send measurement time parameter\n");
		    return;
		}
		tcom_state=1;
		mDumpTextView.append("Initialized\n");
            } catch (IOException e) {
		    mDumpTextView.append("IOException\n");
		    return;
	    }
	    
        }
    }

    /**
     * Starts the activity, using the supplied driver instance.
     *
     * @param context
     * @param driver
     */
    static void show(Context context, UsbSerialDriver driver) {
        sDriver = driver;
        final Intent intent = new Intent(context, SerialConsoleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_HISTORY);
        context.startActivity(intent);
    }

    private final byte STX=0x02;
    private final byte ETX=0x03;
    private final byte ACK=0x06;
    private final static byte[] HEX_DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };
    private static final int RW_WAIT_MILLIS = 10000;
    private static final byte[] TCOM_DOSERATE = {'8','B','0'};
    private static final byte[] TCOM_MES30SEC = {'8','0','2'};
    private static final byte[] TCOM_MES10SEC = {'8','0','1'};
    private static final byte[] TCOM_MES03SEC = {'8','0','0'};
    private static final byte[] TCOM_SPECTRUM = {'8','B','1'};
    private int tcom_state=0;

    private int send_data(byte[] data) throws IOException 
    {
	int parity=0;
	byte[] sdata;
	if(sDriver==null) return -1;

	sdata=new byte[data.length+4];
	sdata[0]=STX;
	for(int i=0;i<data.length;i++){
	    sdata[i+1]=data[i];
	    parity^=data[i];
	}
	sdata[data.length+1]=ETX;
	sdata[data.length+2]=HEX_DIGITS[(parity>>4)&0x0F];
	sdata[data.length+3]=HEX_DIGITS[parity&0x0F];
	mDumpTextView.append(HexDump.dumpHexString(sdata)+'\n');
	return sDriver.write(sdata, RW_WAIT_MILLIS);
    }

    private int send_wait_ack(byte[] data) throws IOException
    {
	byte[] recd=new byte[1];
        if(send_data(data)<0) return -1;
        if(sDriver.read(recd, RW_WAIT_MILLIS)!=1) return -1;
        if(recd[0]!=ACK) return -1;
	return 0;
    }

    private byte[] rec_data_tout() throws IOException
    {
	byte[] recd=new byte[128];
        byte[] rss=new byte[2];
	int parity=0;
	int rstate=0;
	int reclen;

	// all data comes at one time
	if((reclen=sDriver.read(recd, RW_WAIT_MILLIS))==0) {
	    mDumpTextView.append("Timed Out ro receive data\n");
	    return null;
	}
	if(recd[0]!=STX){
	    mDumpTextView.append("No STX\n");
	    return null;
	}
        for(int i=0;i<reclen;i++){
	    switch(rstate){
	    case 0:
		if(recd[i]!=STX) break;
		rstate=1;
		break;
	    case 1:
		if(recd[i]==ETX){
		    rstate=2;
		    break;
		}
		parity^=recd[i];
		break;
	    case 2:
		rss[0]=recd[i];
		rstate=3;
		break;
	    case 3:
		rss[1]=recd[i];
		int rb=Integer.parseInt(new String(rss, 0,2),16);
		if(rb!=parity) {
		    mDumpTextView.append("parity doesn't match\n");
		    return null;
		}
		return Arrays.copyOfRange(recd,1,i-2); // parity matched
	    }
	}
	mDumpTextView.append("Garbage?\n");
	return null;
    }

    private int get_dose_rate(byte[] data)
    {
	mDumpTextView.append(HexDump.dumpHexString(data)+'\n');
	if(data[0]!='0' || data[1]!='1') return -1;
	return Integer.parseInt(new String(data, 2, data.length-2),16);
    }

    

    /** Called when the user clicks the Read button */
    public void readData(View view) {
    byte[] rdata=null;
	int doserate;
        if (tcom_state==1) {
            try {
		mDumpTextView.append("Read one data\n");
		if(send_data(new byte[] {'0','1'})<0){
		    mDumpTextView.append("send_data 01 error\n");
		    return;
		}
		rdata=rec_data_tout();
		if(rdata==null){
		    mDumpTextView.append("rec_data_tout error\n");
		    return;
		}
		doserate=get_dose_rate(rdata);
		if(send_data(new byte[] {'0','2'})<0){
		    mDumpTextView.append("send_data 02 error\n");
		    return;
		}
		mDumpTextView.append(String.format("%d\n",doserate));
		return;
            } catch (IOException e) {
		mDumpTextView.append("IOException\n");
	    }

	}else{
	    mDumpTextView.append("Not Connected\n");
	}
    }
}

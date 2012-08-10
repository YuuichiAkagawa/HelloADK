/*
 * Copyright (C) 2012 Yuuichi Akagawa
 *
 * This code is modify from yanzm's HelloADK.
 * https://github.com/yanzm/HelloADK
 * Original copyright: 
 *  Copyright (C) 2011 yanzm, uPhyca Inc.,
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ammlab.android.helloadk;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import com.android.future.usb.UsbAccessory;
import com.android.future.usb.UsbManager;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.ToggleButton;

public class HelloADKActivity extends Activity implements Runnable {

    private static final String TAG = "HelloADK";

    private static final String ACTION_USB_PERMISSION = "org.ammlab.android.app.helloadk.action.USB_PERMISSION";

    private PendingIntent mPermissionIntent;
    private boolean mPermissionRequestPending;

    private UsbManager mUsbManager;
    private UsbAccessory mAccessory;

    ParcelFileDescriptor mFileDescriptor;

    FileInputStream mInputStream;
    FileOutputStream mOutputStream;
    
    private ToggleButton mToggleButton;
    private ToggleButton mBtnStatusButton;
    private TextView mStatusView;
    private SeekBar mSeekBar;

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbAccessory accessory = UsbManager.getAccessory(intent);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        openAccessory(accessory);
                    } else {
                        Log.d(TAG, "permission denied for accessory " + accessory);
                    }
                    mPermissionRequestPending = false;
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                UsbAccessory accessory = UsbManager.getAccessory(intent);
                if (accessory != null && accessory.equals(mAccessory)) {
                    closeAccessory();
                }
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUsbManager = UsbManager.getInstance(this);

        // Broadcast Intent for myPermission
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);

        // Register Intent myPermission and remove accessory
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        registerReceiver(mUsbReceiver, filter);

/////////////////////////////////////////////////////////////////////////////        
		if (getLastNonConfigurationInstance() != null) {
			mAccessory = (UsbAccessory) getLastNonConfigurationInstance();
			openAccessory(mAccessory);
		}
/////////////////////////////////////////////////////////////////////////////        
        
        setContentView(R.layout.main);
        
        mToggleButton = (ToggleButton) findViewById(R.id.toggleBtn);
        mStatusView = (TextView) findViewById(R.id.status);
        mBtnStatusButton= (ToggleButton) findViewById(R.id.btnstatusBtn);
        mSeekBar = (SeekBar) findViewById(R.id.seekBar1);
        
        mToggleButton.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                byte command = 0x1;
                byte value = (byte) (isChecked ? 0x1 : 0x0);
                sendCommand(command, value);
            }
        });
        
        mSeekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
        	@Override
        	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        		// Dragging knob
        		byte value = (byte)(progress*255/100);
        		byte command = 0x2;
        		Log.d(TAG, "Current Value:"+progress+","+value);
        		sendCommand(command, value);
        	}
 
        	@Override
        	public void onStartTrackingTouch(SeekBar seekBar) {
        		// Touch knob
        	}
 
        	@Override
        	public void onStopTrackingTouch(SeekBar seekBar) {
        		// Release knob
        		//mSeekBar.setProgress(50);
        	}
        });

        enableControls(false);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mInputStream != null && mOutputStream != null) {
            return;
        }

        UsbAccessory[] accessories = mUsbManager.getAccessoryList();
        UsbAccessory accessory = (accessories == null ? null : accessories[0]);
        if (accessory != null) {
            if (mUsbManager.hasPermission(accessory)) {
                openAccessory(accessory);
            } else {
                synchronized (mUsbReceiver) {
                    if (!mPermissionRequestPending) {
                        mUsbManager.requestPermission(accessory, mPermissionIntent);
                        mPermissionRequestPending = true;
                    }
                }
            }
        } else {
            Log.d(TAG, "mAccessory is null");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        closeAccessory();
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mUsbReceiver);
        super.onDestroy();
    }

    private void openAccessory(UsbAccessory accessory) {
        mFileDescriptor = mUsbManager.openAccessory(accessory);

        if (mFileDescriptor != null) {
            mAccessory = accessory;
            FileDescriptor fd = mFileDescriptor.getFileDescriptor();

            mInputStream = new FileInputStream(fd);
            mOutputStream = new FileOutputStream(fd);

            // communication thread start
            Thread thread = new Thread(null, this, "DemoKit");
            thread.start();
            Log.d(TAG, "accessory opened");

            enableControls(true);
        } else {
            Log.d(TAG, "accessory open fail");
        }
    }

    private void closeAccessory() {
        enableControls(false);

        try {
            if (mFileDescriptor != null) {
                mFileDescriptor.close();
            }
        } catch (IOException e) {
        } finally {
            mFileDescriptor = null;
            mAccessory = null;
        }
    }

    private void enableControls(boolean enable) {
        if (enable) {
            mStatusView.setText("connected");
        } else {
            mStatusView.setText("not connect");
        }
        mToggleButton.setEnabled(enable);
    }

    private static final int MESSAGE_LED = 1;

    // USB read thread
    @Override
    public void run() {
        int ret = 0;
        byte[] buffer = new byte[16384];
        int i;

        // Accessory -> Android
        while (ret >= 0) {
            try {
            	ret = mInputStream.read(buffer);
            } catch (IOException e) {
            	e.printStackTrace();
                break;
            }

            if( ret > 0 ){
                Log.d(TAG, ret + " bytes message received.");
            }
            i = 0;
            while (i < ret) {
                int len = ret - i;

                switch (buffer[i]) {
                    case 0x1:
                        if (len >= 2) {
                            Message m = Message.obtain(mHandler, MESSAGE_LED);
                            m.arg1 = (int)buffer[i + 1];
                            mHandler.sendMessage(m);
                            i += 2;
                        }
                        break;

                    default:
                        Log.d(TAG, "unknown msg: " + buffer[i]);
                        i = len;
                        break;
                }
            }

        }
    }

    // Change the view in the UI thread
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_LED:
                    if(msg.arg1 == 0){
                    	mBtnStatusButton.setChecked(false);
                    }else{
                    	mBtnStatusButton.setChecked(true);
                    }
                    break;
            }
        }
    };

    // Android -> Accessory
    public void sendCommand(byte command, byte value) {
        byte[] buffer = new byte[2];
        buffer[0] = command;
        buffer[1] = value;
        if (mOutputStream != null) {
            try {
                mOutputStream.write(buffer);
            } catch (IOException e) {
                Log.e(TAG, "write failed", e);
            }
        }
    }
}

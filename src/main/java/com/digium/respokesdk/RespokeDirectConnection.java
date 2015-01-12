package com.digium.respokesdk;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.PeerConnection;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

/**
 * Created by jasonadams on 12/3/14.
 */
public class RespokeDirectConnection implements org.webrtc.DataChannel.Observer {

    private WeakReference<Listener> listenerReference;
    private WeakReference<RespokeCall> callReference;
    private DataChannel dataChannel;


    /**
     *  A listener interface to notify the receiver of events occurring with the direct connection
     */
    public interface Listener {

        /**
         *  The direct connection setup has begun. This does NOT mean it's ready to send messages yet. Listen to
         *  onOpen for that notification.
         *
         *  @param sender  The direct connection for which the event occurred
         */
        public void onStart(RespokeDirectConnection sender);

        /**
         *  Called when the direct connection is opened.
         *
         *  @param sender  The direct connection for which the event occurred
         */
        public void onOpen(RespokeDirectConnection sender);

        /**
         *  Called when the direct connection is closed.
         *
         *  @param sender  The direct connection for which the event occurred
         */
        public void onClose(RespokeDirectConnection sender);

        /**
         *  Called when a message is received over the direct connection.
         *
         *  @param sender  The direct connection for which the event occurred
         */
        public void onMessage(String message, RespokeDirectConnection sender);

    }


    public RespokeDirectConnection(RespokeCall call) {
        callReference = new WeakReference<RespokeCall>(call);
    }


    public void setListener(Listener listener) {
        if (null != listener) {
            listenerReference = new WeakReference<Listener>(listener);
        } else {
            listenerReference = null;
        }
    }


    public void accept() {
        RespokeCall call = callReference.get();
        if (null != call) {
            call.directConnectionDidAccept(this);
        }
    }


    public boolean isActive() {
        return ((null != dataChannel) && (dataChannel.state() == DataChannel.State.OPEN));
    }


    public RespokeCall getCall() {
        return callReference.get();
    }


    public void sendMessage(String message, final Respoke.TaskCompletionListener completionListener) {
        if (isActive()) {
            JSONObject jsonMessage = new JSONObject();
            try {
                jsonMessage.put("message", message);
                byte[] rawMessage = jsonMessage.toString().getBytes(Charset.forName("UTF-8"));
                ByteBuffer directData = ByteBuffer.allocateDirect(rawMessage.length);
                directData.put(rawMessage);
                directData.flip();
                DataChannel.Buffer data = new DataChannel.Buffer(directData, false);

                if (dataChannel.send(data)) {
                    completionListener.onSuccess();
                } else {
                    completionListener.onError("Error sending message");
                }
            } catch (JSONException e) {
                completionListener.onError("Unable to encode message to JSON");
            }
        } else {
            completionListener.onError("dataChannel not in an open state");
        }
    }


    public void createDataChannel() {
        RespokeCall call = callReference.get();
        if (null != call) {
            PeerConnection peerConnection = call.getPeerConnection();
            dataChannel = peerConnection.createDataChannel("respokeDataChannel", new DataChannel.Init());
            dataChannel.registerObserver(this);
        }
    }


    public void peerConnectionDidOpenDataChannel(DataChannel newDataChannel) {
        if (null != dataChannel) {
            // Replacing the previous connection, so disable observer messages from the old instance
            dataChannel.unregisterObserver();
        } else {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    Listener listener = listenerReference.get();
                    if (null != listener) {
                        listener.onStart(RespokeDirectConnection.this);
                    }
                }
            });
        }

        dataChannel = newDataChannel;
        newDataChannel.registerObserver(this);
    }


    // org.webrtc.DataChannel.Observer methods


    public void onStateChange() {
        switch (dataChannel.state()) {
            case CONNECTING:
                break;

            case OPEN: {
                    RespokeCall call = callReference.get();
                    if (null != call) {
                        call.directConnectionDidOpen(this);
                    }
                }
                break;

            case CLOSING:
                break;

            case CLOSED: {
                    RespokeCall call = callReference.get();
                    if (null != call) {
                        call.directConnectionDidClose(this);
                    }

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            Listener listener = listenerReference.get();
                            if (null != listener) {
                                listener.onClose(RespokeDirectConnection.this);
                            }
                        }
                    });
                }
                break;
        }
    }


    public void onMessage(org.webrtc.DataChannel.Buffer buffer) {
        if (buffer.binary) {
            // TODO
        } else {
            Charset charset = Charset.forName("UTF-8");
            CharsetDecoder decoder = charset.newDecoder();
            try {
                String message = decoder.decode( buffer.data ).toString();

                try {
                    JSONObject jsonMessage = new JSONObject(message);
                    final String messageText = jsonMessage.getString("message");

                    if (null != messageText) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            public void run() {
                                Listener listener = listenerReference.get();
                                if (null != listener) {
                                    listener.onMessage(messageText, RespokeDirectConnection.this);
                                }
                            }
                        });
                    }
                } catch (JSONException e) {
                    // If it is not valid json, ignore the message
                }
            } catch (CharacterCodingException e) {
                // If the message can not be decoded, ignore it
            }
        }
    }


}
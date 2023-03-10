/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telecom.AudioState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.GatewayInfo;
import android.telecom.ParcelableConference;
import android.telecom.ParcelableConnection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IConnectionServiceAdapter;
import com.android.internal.telecom.IVideoProvider;
import com.android.internal.telecom.RemoteServiceCallback;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper for {@link IConnectionService}s, handles binding to {@link IConnectionService} and keeps
 * track of when the object can safely be unbound. Other classes should not use
 * {@link IConnectionService} directly and instead should use this class to invoke methods of
 * {@link IConnectionService}.
 */
final class ConnectionServiceWrapper extends ServiceBinder<IConnectionService> {
    private static final int MSG_HANDLE_CREATE_CONNECTION_COMPLETE = 1;
    private static final int MSG_SET_ACTIVE = 2;
    private static final int MSG_SET_RINGING = 3;
    private static final int MSG_SET_DIALING = 4;
    private static final int MSG_SET_DISCONNECTED = 5;
    private static final int MSG_SET_ON_HOLD = 6;
    private static final int MSG_SET_RINGBACK_REQUESTED = 7;
    private static final int MSG_SET_CALL_CAPABILITIES = 8;
    private static final int MSG_SET_IS_CONFERENCED = 9;
    private static final int MSG_ADD_CONFERENCE_CALL = 10;
    private static final int MSG_REMOVE_CALL = 11;
    private static final int MSG_ON_POST_DIAL_WAIT = 12;
    private static final int MSG_QUERY_REMOTE_CALL_SERVICES = 13;
    private static final int MSG_SET_VIDEO_PROVIDER = 14;
    private static final int MSG_SET_IS_VOIP_AUDIO_MODE = 15;
    private static final int MSG_SET_STATUS_HINTS = 16;
    private static final int MSG_SET_ADDRESS = 17;
    private static final int MSG_SET_CALLER_DISPLAY_NAME = 18;
    private static final int MSG_SET_VIDEO_STATE = 19;
    private static final int MSG_SET_CONFERENCEABLE_CONNECTIONS = 20;
    private static final int MSG_SET_EXTRAS = 21;
    private static final int MSG_SET_DISCONNECTED_WITH_SUPP_NOTIFICATION = 22;
    private static final int MSG_SET_PHONE_ACCOUNT = 23;
    private static final int MSG_SET_CALL_SUBSTATE = 24;
    private static final int MSG_ADD_EXISTING_CONNECTION = 25;
    private static final int MSG_SET_CALL_PROPERTIES = 26;
    private static final int MSG_RESET_CDMA_CONNECT_TIME = 27;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Call call;
            switch (msg.what) {
                case MSG_HANDLE_CREATE_CONNECTION_COMPLETE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        ConnectionRequest request = (ConnectionRequest) args.arg2;
                        ParcelableConnection connection = (ParcelableConnection) args.arg3;
                        handleCreateConnectionComplete(callId, request, connection);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_ACTIVE:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.markCallAsActive(call);
                    } else {
                        //Log.w(this, "setActive, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_SET_EXTRAS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        Bundle extras = (Bundle) args.arg2;
                        call = mCallIdMapper.getCall(callId);
                        if (call != null) {
                            mCallsManager.setCallExtras(call, extras);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_RINGING:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.markCallAsRinging(call);
                    } else {
                        //Log.w(this, "setRinging, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_SET_DIALING:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.markCallAsDialing(call);
                    } else {
                        //Log.w(this, "setDialing, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_SET_DISCONNECTED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        DisconnectCause disconnectCause = (DisconnectCause) args.arg2;
                        Log.d(this, "disconnect call %s %s", disconnectCause, call);
                        if (call != null) {
                            mCallsManager.markCallAsDisconnected(call, disconnectCause);
                        } else {
                            //Log.w(this, "setDisconnected, unknown call id: %s", args.arg1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_ON_HOLD:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.markCallAsOnHold(call);
                    } else {
                        //Log.w(this, "setOnHold, unknown call id: %s", msg.obj);
                    }
                    break;
                case MSG_SET_RINGBACK_REQUESTED: {
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.setRingbackRequested(msg.arg1 == 1);
                    } else {
                        //Log.w(this, "setRingback, unknown call id: %s", args.arg1);
                    }
                    break;
                }
                case MSG_SET_CALL_CAPABILITIES: {
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.setCallCapabilities(msg.arg1);
                    } else {
                        //Log.w(ConnectionServiceWrapper.this,
                        //      "setCallCapabilities, unknown call id: %s", msg.obj);
                    }
                    break;
                }
                case MSG_SET_CALL_PROPERTIES: {
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.setCallProperties(msg.arg1);
                    } else {
                        //Log.w(ConnectionServiceWrapper.this,
                        //      "setCallProperties, unknown call id: %s", msg.obj);
                    }
                    break;
                }
                case MSG_SET_IS_CONFERENCED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Call childCall = mCallIdMapper.getCall(args.arg1);
                        Log.d(this, "SET_IS_CONFERENCE: %s %s", args.arg1, args.arg2);
                        if (childCall != null) {
                            String conferenceCallId = (String) args.arg2;
                            if (conferenceCallId == null) {
                                Log.d(this, "unsetting parent: %s", args.arg1);
                                childCall.setParentCall(null);
                            } else {
                                Call conferenceCall = mCallIdMapper.getCall(conferenceCallId);
                                childCall.setParentCall(conferenceCall);
                            }
                        } else {
                            //Log.w(this, "setIsConferenced, unknown call id: %s", args.arg1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_ADD_CONFERENCE_CALL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String id = (String) args.arg1;
                        if (mCallIdMapper.getCall(id) != null) {
                            Log.w(this, "Attempting to add a conference call using an existing " +
                                    "call id %s", id);
                            break;
                        }
                        ParcelableConference parcelableConference =
                                (ParcelableConference) args.arg2;

                        // need to create a new Call
                        PhoneAccountHandle phAcc = null;
                        if (parcelableConference != null) {
                            phAcc = parcelableConference.getPhoneAccount();
                        }
                        Call conferenceCall = mCallsManager.createConferenceCall(
                                phAcc, parcelableConference);
                        mCallIdMapper.addCall(conferenceCall, id);
                        conferenceCall.setConnectionService(ConnectionServiceWrapper.this);

                        Log.d(this, "adding children to conference %s phAcc %s",
                                parcelableConference.getConnectionIds(), phAcc);
                        for (String callId : parcelableConference.getConnectionIds()) {
                            Call childCall = mCallIdMapper.getCall(callId);
                            Log.d(this, "found child: %s", callId);
                            if (conferenceCall.getTargetPhoneAccount() == null) {
                                PhoneAccountHandle ph = childCall.getTargetPhoneAccount();
                                conferenceCall.setTargetPhoneAccount(ph);
                            }
                            if (childCall != null) {
                                childCall.setParentCall(conferenceCall);
                            }
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_REMOVE_CALL: {
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        if (call.isAlive()) { //Any Call that is alive
                            mCallsManager.markCallAsDisconnected(
                                    call, new DisconnectCause(DisconnectCause.REMOTE));
                        } else {
                            mCallsManager.markCallAsRemoved(call);
                        }
                    }
                    break;
                }
                case MSG_ON_POST_DIAL_WAIT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        if (call != null) {
                            String remaining = (String) args.arg2;
                            call.onPostDialWait(remaining);
                        } else {
                            //Log.w(this, "onPostDialWait, unknown call id: %s", args.arg1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_QUERY_REMOTE_CALL_SERVICES: {
                    queryRemoteConnectionServices((RemoteServiceCallback) msg.obj);
                    break;
                }
                case MSG_SET_VIDEO_PROVIDER: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        IVideoProvider videoProvider = (IVideoProvider) args.arg2;
                        if (call != null) {
                            call.setVideoProvider(videoProvider);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_IS_VOIP_AUDIO_MODE: {
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.setIsVoipAudioMode(msg.arg1 == 1);
                    }
                    break;
                }
                case MSG_SET_STATUS_HINTS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        StatusHints statusHints = (StatusHints) args.arg2;
                        if (call != null) {
                            call.setStatusHints(statusHints);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_ADDRESS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        if (call != null) {
                            call.setHandle((Uri) args.arg2, args.argi1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_CALLER_DISPLAY_NAME: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        if (call != null) {
                            call.setCallerDisplayName((String) args.arg2, args.argi1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_VIDEO_STATE: {
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.setVideoState(msg.arg1);
                    }
                    break;
                }
                case MSG_SET_CONFERENCEABLE_CONNECTIONS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        if (call != null ){
                            @SuppressWarnings("unchecked")
                            List<String> conferenceableIds = (List<String>) args.arg2;
                            List<Call> conferenceableCalls =
                                    new ArrayList<>(conferenceableIds.size());
                            for (String otherId : (List<String>) args.arg2) {
                                Call otherCall = mCallIdMapper.getCall(otherId);
                                if (otherCall != null && otherCall != call) {
                                    conferenceableCalls.add(otherCall);
                                }
                            }
                            call.setConferenceableCalls(conferenceableCalls);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_PHONE_ACCOUNT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        call = mCallIdMapper.getCall(args.arg1);
                        if (call != null) {
                            PhoneAccountHandle pHandle = (PhoneAccountHandle) args.arg2;
                            call.setTargetPhoneAccount(pHandle);
                        } else {
                            Log.w(this, "setPhoneAccountHandle, unknown call id: %s", args.arg1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_CALL_SUBSTATE: {
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        call.setCallSubstate(msg.arg1);
                    }
                    break;
                }
                case MSG_ADD_EXISTING_CONNECTION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String)args.arg1;
                        ParcelableConnection connection = (ParcelableConnection)args.arg2;
                        Call existingCall = mCallsManager.createCallForExistingConnection(callId,
                                connection);
                        mCallIdMapper.addCall(existingCall, callId);
                        existingCall.setConnectionService(ConnectionServiceWrapper.this);
                    } finally {
                        args.recycle();
                    }
                }
                case MSG_RESET_CDMA_CONNECT_TIME:
                    call = mCallIdMapper.getCall(msg.obj);
                    if (call != null) {
                        mCallsManager.resetCdmaConnectionTime(call);
                    }
                    break;
            }
        }
    };

    private final class Adapter extends IConnectionServiceAdapter.Stub {

        @Override
        public void handleCreateConnectionComplete(
                String callId,
                ConnectionRequest request,
                ParcelableConnection connection) {
            logIncoming("handleCreateConnectionComplete %s", request);
            if (mCallIdMapper.isValidCallId(callId)) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = request;
                args.arg3 = connection;
                mHandler.obtainMessage(MSG_HANDLE_CREATE_CONNECTION_COMPLETE, args)
                        .sendToTarget();
            }
        }

        @Override
        public void setActive(String callId) {
            logIncoming("setActive %s", callId);
            if (mCallIdMapper.isValidCallId(callId) || mCallIdMapper.isValidConferenceId(callId)) {
                mHandler.obtainMessage(MSG_SET_ACTIVE, callId).sendToTarget();
            }
        }

        @Override
        public void setExtras(String callId, Bundle extras) {
            logIncoming("setExtras size= " + extras.size() + " | callId= " + callId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = extras;
            mHandler.obtainMessage(MSG_SET_EXTRAS, args).sendToTarget();
        }

        @Override
        public void setRinging(String callId) {
            logIncoming("setRinging %s", callId);
            if (mCallIdMapper.isValidCallId(callId)) {
                mHandler.obtainMessage(MSG_SET_RINGING, callId).sendToTarget();
            }
        }

        @Override
        public void setVideoProvider(String callId, IVideoProvider videoProvider) {
            logIncoming("setVideoProvider %s", callId);
            if (mCallIdMapper.isValidCallId(callId) || mCallIdMapper.isValidConferenceId(callId)) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = videoProvider;
                mHandler.obtainMessage(MSG_SET_VIDEO_PROVIDER, args).sendToTarget();
            }
        }

        @Override
        public void setDialing(String callId) {
            logIncoming("setDialing %s", callId);
            if (mCallIdMapper.isValidCallId(callId)) {
                mHandler.obtainMessage(MSG_SET_DIALING, callId).sendToTarget();
            }
        }

        @Override
        public void setDisconnected(String callId, DisconnectCause disconnectCause) {
            logIncoming("setDisconnected %s %s", callId, disconnectCause);
            if (mCallIdMapper.isValidCallId(callId) || mCallIdMapper.isValidConferenceId(callId)) {
                Log.d(this, "disconnect call %s", callId);
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = disconnectCause;
                mHandler.obtainMessage(MSG_SET_DISCONNECTED, args).sendToTarget();
            }
        }

        @Override
        public void setOnHold(String callId) {
            logIncoming("setOnHold %s", callId);
            if (mCallIdMapper.isValidCallId(callId) || mCallIdMapper.isValidConferenceId(callId)) {
                mHandler.obtainMessage(MSG_SET_ON_HOLD, callId).sendToTarget();
            }
        }

        @Override
        public void setRingbackRequested(String callId, boolean ringback) {
            logIncoming("setRingbackRequested %s %b", callId, ringback);
            if (mCallIdMapper.isValidCallId(callId)) {
                mHandler.obtainMessage(MSG_SET_RINGBACK_REQUESTED, ringback ? 1 : 0, 0, callId)
                        .sendToTarget();
            }
        }

        @Override
        public void removeCall(String callId) {
            logIncoming("removeCall %s", callId);
            if (mCallIdMapper.isValidCallId(callId) || mCallIdMapper.isValidConferenceId(callId)) {
                mHandler.obtainMessage(MSG_REMOVE_CALL, callId).sendToTarget();
            }
        }

        @Override
        public void setCallCapabilities(String callId, int callCapabilities) {
            logIncoming("setCallCapabilities %s %d", callId, callCapabilities);
            if (mCallIdMapper.isValidCallId(callId) || mCallIdMapper.isValidConferenceId(callId)) {
                mHandler.obtainMessage(MSG_SET_CALL_CAPABILITIES, callCapabilities, 0, callId)
                        .sendToTarget();
            } else {
                Log.w(this, "ID not valid for setCallCapabilities");
            }
        }

        @Override
        public void setCallProperties(String callId, int callProperties) {
            logIncoming("setCallProperties %s %x", callId, callProperties);
            if (mCallIdMapper.isValidCallId(callId) || mCallIdMapper.isValidConferenceId(callId)) {
                mHandler.obtainMessage(MSG_SET_CALL_PROPERTIES, callProperties, 0, callId)
                        .sendToTarget();
            } else {
                Log.w(this, "ID not valid for setCallProperties");
            }
        }

        @Override
        public void setIsConferenced(String callId, String conferenceCallId) {
            logIncoming("setIsConferenced %s %s", callId, conferenceCallId);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = conferenceCallId;
            mHandler.obtainMessage(MSG_SET_IS_CONFERENCED, args).sendToTarget();
        }

        @Override
        public void addConferenceCall(String callId, ParcelableConference parcelableConference) {
            logIncoming("addConferenceCall %s %s", callId, parcelableConference);
            // We do not check call Ids here because we do not yet know the call ID for new
            // conference calls.
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = parcelableConference;
            mHandler.obtainMessage(MSG_ADD_CONFERENCE_CALL, args).sendToTarget();
        }

        @Override
        public void onPostDialWait(String callId, String remaining) throws RemoteException {
            logIncoming("onPostDialWait %s %s", callId, remaining);
            if (mCallIdMapper.isValidCallId(callId)) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = remaining;
                mHandler.obtainMessage(MSG_ON_POST_DIAL_WAIT, args).sendToTarget();
            }
        }

        @Override
        public void queryRemoteConnectionServices(RemoteServiceCallback callback) {
            logIncoming("queryRemoteCSs");
            mHandler.obtainMessage(MSG_QUERY_REMOTE_CALL_SERVICES, callback).sendToTarget();
        }

        @Override
        public void setVideoState(String callId, int videoState) {
            logIncoming("setVideoState %s %d", callId, videoState);
            if (mCallIdMapper.isValidCallId(callId) || mCallIdMapper.isValidConferenceId(callId)) {
                mHandler.obtainMessage(MSG_SET_VIDEO_STATE, videoState, 0, callId).sendToTarget();
            }
        }

        @Override
        public void setIsVoipAudioMode(String callId, boolean isVoip) {
            logIncoming("setIsVoipAudioMode %s %b", callId, isVoip);
            if (mCallIdMapper.isValidCallId(callId)) {
                mHandler.obtainMessage(MSG_SET_IS_VOIP_AUDIO_MODE, isVoip ? 1 : 0, 0,
                        callId).sendToTarget();
            }
        }

        @Override
        public void setStatusHints(String callId, StatusHints statusHints) {
            logIncoming("setStatusHints %s %s", callId, statusHints);
            if (mCallIdMapper.isValidCallId(callId)) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = statusHints;
                mHandler.obtainMessage(MSG_SET_STATUS_HINTS, args).sendToTarget();
            }
        }

        @Override
        public void setAddress(String callId, Uri address, int presentation) {
            logIncoming("setAddress %s %s %d", callId, address, presentation);
            if (mCallIdMapper.isValidCallId(callId)) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = address;
                args.argi1 = presentation;
                mHandler.obtainMessage(MSG_SET_ADDRESS, args).sendToTarget();
            }
        }

        @Override
        public void setCallerDisplayName(
                String callId, String callerDisplayName, int presentation) {
            logIncoming("setCallerDisplayName %s %s %d", callId, callerDisplayName, presentation);
            if (mCallIdMapper.isValidCallId(callId)) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = callerDisplayName;
                args.argi1 = presentation;
                mHandler.obtainMessage(MSG_SET_CALLER_DISPLAY_NAME, args).sendToTarget();
            }
        }

        @Override
        public void setConferenceableConnections(
                String callId, List<String> conferenceableCallIds) {
            logIncoming("setConferenceableConnections %s %s", callId, conferenceableCallIds);
            if (mCallIdMapper.isValidCallId(callId) || mCallIdMapper.isValidConferenceId(callId)) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = conferenceableCallIds;
                mHandler.obtainMessage(MSG_SET_CONFERENCEABLE_CONNECTIONS, args).sendToTarget();
            }
        }

        @Override
        public void setPhoneAccountHandle(String callId, PhoneAccountHandle pHandle) {
            logIncoming("setPhoneAccountHandle %s %s", callId, pHandle);
            if (mCallIdMapper.isValidCallId(callId)) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = pHandle;
                mHandler.obtainMessage(MSG_SET_PHONE_ACCOUNT, args).sendToTarget();
            }
        }

        @Override
        public void setCallSubstate(String callId, int callSubstate) {
            logIncoming("setCallSubstate %s %d", callId, callSubstate);
            if (mCallIdMapper.isValidCallId(callId)) {
                mHandler.obtainMessage(
                    MSG_SET_CALL_SUBSTATE, callSubstate, 0, callId).sendToTarget();
            }
        }

        @Override
        public void addExistingConnection(String callId, ParcelableConnection connection) {
            logIncoming("addExistingConnection  %s %s", callId, connection);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = connection;
            mHandler.obtainMessage(MSG_ADD_EXISTING_CONNECTION, args).sendToTarget();
       }

       public void resetCdmaConnectionTime(String callId) {
            logIncoming("resetCdmaConnectionTime");
            if (mCallIdMapper.isValidCallId(callId) || mCallIdMapper.isValidConferenceId(callId)) {
                mHandler.obtainMessage(MSG_RESET_CDMA_CONNECT_TIME, callId).sendToTarget();
            }
        }
    }

    private final Adapter mAdapter = new Adapter();
    private final CallsManager mCallsManager = CallsManager.getInstance();
    /**
     * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
     * load factor before resizing, 1 means we only expect a single thread to
     * access the map so make only a single shard
     */
    private final Set<Call> mPendingConferenceCalls = Collections.newSetFromMap(
            new ConcurrentHashMap<Call, Boolean>(8, 0.9f, 1));
    private final CallIdMapper mCallIdMapper = new CallIdMapper("ConnectionService");
    private final Map<String, CreateConnectionResponse> mPendingResponses = new HashMap<>();

    private Binder mBinder = new Binder();
    private IConnectionService mServiceInterface;
    private final ConnectionServiceRepository mConnectionServiceRepository;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;

    /**
     * Creates a connection service.
     *
     * @param componentName The component name of the service with which to bind.
     * @param connectionServiceRepository Connection service repository.
     * @param phoneAccountRegistrar Phone account registrar
     * @param context The context.
     */
    ConnectionServiceWrapper(
            ComponentName componentName,
            ConnectionServiceRepository connectionServiceRepository,
            PhoneAccountRegistrar phoneAccountRegistrar,
            Context context) {
        super(ConnectionService.SERVICE_INTERFACE, componentName, context);
        mConnectionServiceRepository = connectionServiceRepository;
        phoneAccountRegistrar.addListener(new PhoneAccountRegistrar.Listener() {
            // TODO -- Upon changes to PhoneAccountRegistrar, need to re-wire connections
            // To do this, we must proxy remote ConnectionService objects
        });
        mPhoneAccountRegistrar = phoneAccountRegistrar;
    }

    /** See {@link IConnectionService#addConnectionServiceAdapter}. */
    private void addConnectionServiceAdapter(IConnectionServiceAdapter adapter) {
        if (isServiceValid("addConnectionServiceAdapter")) {
            try {
                logOutgoing("addConnectionServiceAdapter %s", adapter);
                mServiceInterface.addConnectionServiceAdapter(adapter);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Creates a new connection for a new outgoing call or to attach to an existing incoming call.
     */
    void createConnection(final Call call, final CreateConnectionResponse response) {
        Log.d(this, "createConnection(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                String callId = mCallIdMapper.getCallId(call);
                mPendingResponses.put(callId, response);

                GatewayInfo gatewayInfo = call.getGatewayInfo();
                Bundle extras = call.getExtras();
                if (gatewayInfo != null && gatewayInfo.getGatewayProviderPackageName() != null &&
                        gatewayInfo.getOriginalAddress() != null) {
                    extras = (Bundle) extras.clone();
                    extras.putString(
                            TelecomManager.GATEWAY_PROVIDER_PACKAGE,
                            gatewayInfo.getGatewayProviderPackageName());
                    extras.putParcelable(
                            TelecomManager.GATEWAY_ORIGINAL_ADDRESS,
                            gatewayInfo.getOriginalAddress());
                }

                try {
                    mServiceInterface.createConnection(
                            call.getConnectionManagerPhoneAccount(),
                            callId,
                            new ConnectionRequest(
                                    call.getTargetPhoneAccount(),
                                    call.getHandle(),
                                    extras,
                                    call.getVideoState()),
                            call.isIncoming(),
                            call.isUnknown());
                } catch (RemoteException e) {
                    Log.e(this, e, "Failure to createConnection -- %s", getComponentName());
                    mPendingResponses.remove(callId).handleCreateConnectionFailure(
                            new DisconnectCause(DisconnectCause.ERROR, e.toString()));
                }
            }

            @Override
            public void onFailure() {
                Log.e(this, new Exception(), "Failure to call %s", getComponentName());
                response.handleCreateConnectionFailure(new DisconnectCause(DisconnectCause.ERROR));
            }
        };

        mBinder.bind(callback);
    }

    /** @see ConnectionService#abort(String) */
    void abort(Call call) {
        // Clear out any pending outgoing call data
        final String callId = mCallIdMapper.getCallId(call);

        // If still bound, tell the connection service to abort.
        if (callId != null && isServiceValid("abort")) {
            try {
                logOutgoing("abort %s", callId);
                mServiceInterface.abort(callId);
            } catch (RemoteException e) {
            }
        }

        removeCall(call, new DisconnectCause(DisconnectCause.LOCAL));
    }

    /** @see ConnectionService#hold(String) */
    void hold(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("hold")) {
            try {
                logOutgoing("hold %s", callId);
                mServiceInterface.hold(callId);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#unhold(String) */
    void unhold(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("unhold")) {
            try {
                logOutgoing("unhold %s", callId);
                mServiceInterface.unhold(callId);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#onAudioStateChanged(String,AudioState) */
    void onAudioStateChanged(Call activeCall, AudioState audioState) {
        final String callId = mCallIdMapper.getCallId(activeCall);
        if (callId != null && isServiceValid("onAudioStateChanged")) {
            try {
                logOutgoing("onAudioStateChanged %s %s", callId, audioState);
                mServiceInterface.onAudioStateChanged(callId, audioState);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#disconnect(String) */
    void disconnect(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("disconnect")) {
            try {
                logOutgoing("disconnect %s", callId);
                mServiceInterface.disconnect(callId);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#answer(String,int) */
    void answer(Call call, int videoState) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("answer")) {
            try {
                logOutgoing("answer %s %d", callId, videoState);
                if (videoState == VideoProfile.VideoState.AUDIO_ONLY) {
                    mServiceInterface.answer(callId);
                } else {
                    mServiceInterface.answerVideo(callId, videoState);
                }
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#deflect(String, String) */
    void deflect(Call call, String number) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("deflect")) {
            try {
                logOutgoing("deflect %s %s", callId, number);
                mServiceInterface.deflect(callId, number);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#reject(String) */
    void reject(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("reject")) {
            try {
                logOutgoing("reject %s", callId);
                mServiceInterface.reject(callId);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#playDtmfTone(String,char) */
    void playDtmfTone(Call call, char digit) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("playDtmfTone")) {
            try {
                logOutgoing("playDtmfTone %s %c", callId, digit);
                mServiceInterface.playDtmfTone(callId, digit);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#setLocalCallHold(String,int) */
    void setLocalCallHold(Call call, int lchStatus) {
        if (isServiceValid("SetLocalCallHold")) {
            try {
                logOutgoing("SetLocalCallHold %s %d", mCallIdMapper.getCallId(call), lchStatus);
                mServiceInterface.setLocalCallHold(mCallIdMapper.getCallId(call), lchStatus);
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#setLocalCallHold(String,int) */
    void setActiveSubscription(Call call) {
        if (isServiceValid("setActiveSubscription")) {
            try {
                logOutgoing("setActiveSubscription %s", mCallIdMapper.getCallId(call));
                mServiceInterface.setActiveSubscription(mCallIdMapper.getCallId(call));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see ConnectionService#stopDtmfTone(String) */
    void stopDtmfTone(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("stopDtmfTone")) {
            try {
                logOutgoing("stopDtmfTone %s",callId);
                mServiceInterface.stopDtmfTone(callId);
            } catch (RemoteException e) {
            }
        }
    }

    void addCall(Call call) {
        if (mCallIdMapper.getCallId(call) == null) {
            mCallIdMapper.addCall(call);
        }
    }

    /**
     * Associates newCall with this connection service by replacing callToReplace.
     */
    void replaceCall(Call newCall, Call callToReplace) {
        Preconditions.checkState(callToReplace.getConnectionService() == this);
        mCallIdMapper.replaceCall(newCall, callToReplace);
    }

    void removeCall(Call call) {
        removeCall(call, new DisconnectCause(DisconnectCause.ERROR));
    }

    void removeCall(String callId, DisconnectCause disconnectCause) {
        CreateConnectionResponse response = mPendingResponses.remove(callId);
        if (response != null) {
            response.handleCreateConnectionFailure(disconnectCause);
        }

        mCallIdMapper.removeCall(callId);
    }

    void removeCall(Call call, DisconnectCause disconnectCause) {
        CreateConnectionResponse response = mPendingResponses.remove(mCallIdMapper.getCallId(call));
        if (response != null) {
            response.handleCreateConnectionFailure(disconnectCause);
        }

        mCallIdMapper.removeCall(call);
    }

    void onPostDialContinue(Call call, boolean proceed) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("onPostDialContinue")) {
            try {
                logOutgoing("onPostDialContinue %s %b", callId, proceed);
                mServiceInterface.onPostDialContinue(callId, proceed);
            } catch (RemoteException ignored) {
            }
        }
    }

    void conference(final Call call, Call otherCall) {
        final String callId = mCallIdMapper.getCallId(call);
        final String otherCallId = mCallIdMapper.getCallId(otherCall);
        if (callId != null && otherCallId != null && isServiceValid("conference")) {
            try {
                logOutgoing("conference %s %s", callId, otherCallId);
                mServiceInterface.conference(callId, otherCallId);
            } catch (RemoteException ignored) {
            }
        }
    }

    void splitFromConference(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("splitFromConference")) {
            try {
                logOutgoing("splitFromConference %s", callId);
                mServiceInterface.splitFromConference(callId);
            } catch (RemoteException ignored) {
            }
        }
    }

    void addParticipantWithConference(Call call, String receipant) {
        final String callId = mCallIdMapper.getCallId(call);
            if (isServiceValid("addParticipantWithConference")) {
                try {
                    logOutgoing("addParticipantWithConference %s, %s", receipant, callId);
                    mServiceInterface.addParticipantWithConference(callId, receipant);
                } catch (RemoteException ignored) {
                }
        }
    }

    void mergeConference(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("mergeConference")) {
            try {
                logOutgoing("mergeConference %s", callId);
                mServiceInterface.mergeConference(callId);
            } catch (RemoteException ignored) {
            }
        }
    }

    void swapConference(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("swapConference")) {
            try {
                logOutgoing("swapConference %s", callId);
                mServiceInterface.swapConference(callId);
            } catch (RemoteException ignored) {
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void setServiceInterface(IBinder binder) {
        if (binder == null) {
            // We have lost our service connection. Notify the world that this service is done.
            // We must notify the adapter before CallsManager. The adapter will force any pending
            // outgoing calls to try the next service. This needs to happen before CallsManager
            // tries to clean up any calls still associated with this service.
            handleConnectionServiceDeath();
            CallsManager.getInstance().handleConnectionServiceDeath(this);
            mServiceInterface = null;
        } else {
            mServiceInterface = IConnectionService.Stub.asInterface(binder);
            addConnectionServiceAdapter(mAdapter);
        }
    }

    private void handleCreateConnectionComplete(
            String callId,
            ConnectionRequest request,
            ParcelableConnection connection) {
        // TODO: Note we are not using parameter "request", which is a side effect of our tacit
        // assumption that we have at most one outgoing connection attempt per ConnectionService.
        // This may not continue to be the case.
        if (connection.getState() == Connection.STATE_DISCONNECTED) {
            // A connection that begins in the DISCONNECTED state is an indication of
            // failure to connect; we handle all failures uniformly
            removeCall(callId, connection.getDisconnectCause());
        } else {
            // Successful connection
            if (mPendingResponses.containsKey(callId)) {
                mPendingResponses.remove(callId)
                        .handleCreateConnectionSuccess(mCallIdMapper, connection);
            }
        }
    }

    /**
     * Called when the associated connection service dies.
     */
    private void handleConnectionServiceDeath() {
        if (!mPendingResponses.isEmpty()) {
            CreateConnectionResponse[] responses = mPendingResponses.values().toArray(
                    new CreateConnectionResponse[mPendingResponses.values().size()]);
            mPendingResponses.clear();
            for (int i = 0; i < responses.length; i++) {
                responses[i].handleCreateConnectionFailure(
                        new DisconnectCause(DisconnectCause.ERROR));
            }
        }
        mCallIdMapper.clear();
    }

    private void logIncoming(String msg, Object... params) {
        Log.d(this, "ConnectionService -> Telecom: " + msg, params);
    }

    private void logOutgoing(String msg, Object... params) {
        Log.d(this, "Telecom -> ConnectionService: " + msg, params);
    }

    private void queryRemoteConnectionServices(final RemoteServiceCallback callback) {
        // Only give remote connection services to this connection service if it is listed as
        // the connection manager.
        PhoneAccountHandle simCallManager = mPhoneAccountRegistrar.getSimCallManager();
        Log.d(this, "queryRemoteConnectionServices finds simCallManager = %s", simCallManager);
        if (simCallManager == null ||
                !simCallManager.getComponentName().equals(getComponentName())) {
            noRemoteServices(callback);
            return;
        }

        // Make a list of ConnectionServices that are listed as being associated with SIM accounts
        final Set<ConnectionServiceWrapper> simServices = Collections.newSetFromMap(
                new ConcurrentHashMap<ConnectionServiceWrapper, Boolean>(8, 0.9f, 1));
        for (PhoneAccountHandle handle : mPhoneAccountRegistrar.getCallCapablePhoneAccounts()) {
            PhoneAccount account = mPhoneAccountRegistrar.getPhoneAccount(handle);
            if ((account.getCapabilities() & PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) != 0) {
                ConnectionServiceWrapper service =
                        mConnectionServiceRepository.getService(handle.getComponentName());
                if (service != null) {
                    simServices.add(service);
                }
            }
        }

        final List<ComponentName> simServiceComponentNames = new ArrayList<>();
        final List<IBinder> simServiceBinders = new ArrayList<>();

        Log.v(this, "queryRemoteConnectionServices, simServices = %s", simServices);

        for (ConnectionServiceWrapper simService : simServices) {
            if (simService == this) {
                // Only happens in the unlikely case that a SIM service is also a SIM call manager
                continue;
            }

            final ConnectionServiceWrapper currentSimService = simService;

            currentSimService.mBinder.bind(new BindCallback() {
                @Override
                public void onSuccess() {
                    Log.d(this, "Adding simService %s", currentSimService.getComponentName());
                    simServiceComponentNames.add(currentSimService.getComponentName());
                    simServiceBinders.add(currentSimService.mServiceInterface.asBinder());
                    maybeComplete();
                }

                @Override
                public void onFailure() {
                    Log.d(this, "Failed simService %s", currentSimService.getComponentName());
                    // We know maybeComplete() will always be a no-op from now on, so go ahead and
                    // signal failure of the entire request
                    noRemoteServices(callback);
                }

                private void maybeComplete() {
                    if (simServiceComponentNames.size() == simServices.size()) {
                        setRemoteServices(callback, simServiceComponentNames, simServiceBinders);
                    }
                }
            });
        }
    }

    private void setRemoteServices(
            RemoteServiceCallback callback,
            List<ComponentName> componentNames,
            List<IBinder> binders) {
        try {
            callback.onResult(componentNames, binders);
        } catch (RemoteException e) {
            Log.e(this, e, "Contacting ConnectionService %s",
                    ConnectionServiceWrapper.this.getComponentName());
        }
    }

    private void noRemoteServices(RemoteServiceCallback callback) {
        try {
            callback.onResult(Collections.EMPTY_LIST, Collections.EMPTY_LIST);
        } catch (RemoteException e) {
            Log.e(this, e, "Contacting ConnectionService %s", this.getComponentName());
        }
    }
}

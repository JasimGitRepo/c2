package com.c2c.webrtc

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcManager(
    private val context: Context,
    private val signalingSender: (String) -> Unit,
    private val onConnectionStateChange: (Boolean) -> Unit
) {
    private val eglBase: EglBase by lazy { EglBase.create() }
    private var peerConnectionFactory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null

    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    fun initialize() {
        if (peerConnectionFactory != null) return
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()

        audioDeviceModule.release()
    }

    fun enableLocalAudio() {
        try {
            if (localAudioSource == null) {
                localAudioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
                localAudioTrack = peerConnectionFactory?.createAudioTrack("SERVER_AUDIO_TRACK", localAudioSource)
            }
            localAudioTrack?.setEnabled(true)
            
            val transceiver = peerConnection?.transceivers?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            transceiver?.sender?.setTrack(localAudioTrack, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disableLocalAudio() {
        try {
            localAudioTrack?.setEnabled(false)
            val transceiver = peerConnection?.transceivers?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            transceiver?.sender?.setTrack(null, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun createPeerConnection(isCaller: Boolean, onVideoTrackReceived: ((VideoTrack?) -> Unit)? = null) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject().apply {
                    put("cmd", "webrtc_ice")
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }
                signalingSender(json.toString())
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    onVideoTrackReceived?.invoke(track)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> onConnectionStateChange(true)
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> onConnectionStateChange(false)
                    else -> {}
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(stream: MediaStream) {}
        })

        val videoTransceiver = peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
        )

        val audioTransceiver = peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
        )

        val vTrack = videoTransceiver?.receiver?.track() as? VideoTrack
        if (vTrack != null) {
            onVideoTrackReceived?.invoke(vTrack)
        }

        if (isCaller) {
            peerConnection?.createOffer(SdpObserverImpl("offer"), MediaConstraints())
        }
    }

    fun setAudioDirection(direction: RtpTransceiver.RtpTransceiverDirection) {
        try {
            val transceiver = peerConnection?.transceivers?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            if (transceiver != null && transceiver.direction != direction) {
                transceiver.direction = direction
                peerConnection?.createOffer(SdpObserverImpl("offer"), MediaConstraints())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun setVideoDirection(direction: RtpTransceiver.RtpTransceiverDirection) {
        try {
            val transceiver = peerConnection?.transceivers?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            if (transceiver != null && transceiver.direction != direction) {
                transceiver.direction = direction
                peerConnection?.createOffer(SdpObserverImpl("offer"), MediaConstraints())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setRemoteAudioEnabled(enabled: Boolean) {
        try {
            val transceivers = peerConnection?.transceivers ?: return
            val transceiver = transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            val audioTrack = transceiver?.receiver?.track() as? AudioTrack
            audioTrack?.setEnabled(enabled)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun handleSignalingMessage(json: JSONObject) {
        try {
            val cmd = json.optString("cmd")
            when (cmd) {
                "webrtc_offer" -> {
                    val sdp = SessionDescription(SessionDescription.Type.OFFER, json.getString("sdp"))
                    peerConnection?.setRemoteDescription(SdpObserverImpl("setRemoteOffer"), sdp)
                    peerConnection?.createAnswer(SdpObserverImpl("answer"), MediaConstraints())
                }
                "webrtc_answer" -> {
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp"))
                    peerConnection?.setRemoteDescription(SdpObserverImpl("setRemoteAnswer"), sdp)
                }
                "webrtc_ice" -> {
                    val candidate = IceCandidate(json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate"))
                    peerConnection?.addIceCandidate(candidate)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun terminate() {
        try { peerConnection?.close() } catch (e: Exception) {}
        peerConnection = null
        
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { localAudioSource?.dispose() }
        localAudioSource = null
        
        onConnectionStateChange(false)
    }

    private inner class SdpObserverImpl(val type: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            peerConnection?.setLocalDescription(this, sdp)
            val json = JSONObject().apply {
                put("cmd", if (sdp.type == SessionDescription.Type.OFFER) "webrtc_offer" else "webrtc_answer")
                put("sdp", sdp.description)
            }
            signalingSender(json.toString())
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    fun getEglBaseContext(): EglBase.Context = eglBase.eglBaseContext
}
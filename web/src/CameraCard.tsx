import { translate, useI18n } from "./i18n";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { execTool, openAppSettings } from "./bridge";
import { showResult } from "./toast";

type FacingMode = "environment" | "user";
type Phase = "idle" | "live" | "captured";

/**
 * ```camera fence: an in-conversation live viewfinder. The camera is only
 * requested after the user taps 启动相机, one card may hold the stream at a
 * time, and every track is stopped on unmount / page hide / after a shot.
 */

/** Set while a card holds the camera; a new start stops the previous owner. */
let stopActiveCamera: (() => void) | null = null;

function parseOptions(code: string): { facingMode: FacingMode } {
  const text = code.trim();
  if (!text) return { facingMode: "environment" };
  const parsed: unknown = JSON.parse(text);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(translate("cameraJsonRequired"));
  }
  const facing = (parsed as { facingMode?: unknown }).facingMode;
  return { facingMode: facing === "user" ? "user" : "environment" };
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error(translate("readPhotoFailed")));
    reader.readAsDataURL(blob);
  });
}

function stamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

export function CameraCard({ code }: { code: string }) {
  const { t } = useI18n();
  const options = useMemo(() => {
    try {
      return { facingMode: parseOptions(code).facingMode, error: null as string | null };
    } catch (e) {
      return {
        facingMode: "environment" as FacingMode,
        error: `${translate("cameraArgsInvalid")}：${e instanceof Error ? e.message : String(e)} (${translate("cameraArgsHint")})`,
      };
    }
  }, [code]);

  const [phase, setPhase] = useState<Phase>("idle");
  const [facing, setFacing] = useState<FacingMode>(options.facingMode);
  const [error, setError] = useState<string | null>(null);
  const [permDenied, setPermDenied] = useState(false);
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [savePending, setSavePending] = useState(false);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const mountedRef = useRef(true);
  const phaseRef = useRef<Phase>("idle");
  phaseRef.current = phase;

  const stopTracks = useCallback(() => {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
  }, []);

  // Attach the stream once the <video> element exists for the live phase
  // (and again when the camera is switched while live).
  useEffect(() => {
    if (phase === "live" && videoRef.current && streamRef.current) {
      videoRef.current.srcObject = streamRef.current;
      void videoRef.current.play().catch(() => undefined);
    }
  }, [phase, facing]);

  // Lifecycle: stop everything on unmount and when the page is hidden.
  useEffect(() => {
    mountedRef.current = true;
    const onVisibility = () => {
      if (document.hidden) {
        stopTracks();
        if (phaseRef.current === "live") setPhase("idle");
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      mountedRef.current = false;
      document.removeEventListener("visibilitychange", onVisibility);
      if (stopActiveCamera === stopTracks) stopActiveCamera = null;
      stopTracks();
    };
  }, [stopTracks]);

  const start = async (want: FacingMode) => {
    setError(null);
    setPermDenied(false);
    setDataUrl(null);
    if (!navigator.mediaDevices?.getUserMedia) {
      setPhase("idle");
      setError(translate("cameraUnsupported"));
      return;
    }
    // One live camera card at a time: evict the previous owner first.
    if (stopActiveCamera && stopActiveCamera !== stopTracks) stopActiveCamera();
    stopActiveCamera = stopTracks;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: want } },
      });
      if (!mountedRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      streamRef.current = stream;
      setFacing(want);
      setPhase("live");
    } catch (e) {
      if (!mountedRef.current) return;
      setPhase("idle");
      const name = (e as { name?: string })?.name ?? "";
      const text = String((e as { message?: string })?.message ?? e);
      if (name === "NotAllowedError" || name === "SecurityError" || /permission|denied/i.test(text)) {
        setPermDenied(true);
        setError(translate("cameraDenied"));
      } else if (name === "NotFoundError" || name === "OverconstrainedError") {
        setError(want === "user" ? translate("frontCameraMissing") : translate("rearCameraMissing"));
      } else {
        setError(`${t("cameraStartFailed")}：${text}`);
      }
    }
  };

  const shoot = async () => {
    const video = videoRef.current;
    if (!video || !streamRef.current) return;
    const w = video.videoWidth;
    const h = video.videoHeight;
    if (!w || !h) {
      setError(translate("cameraNotReady"));
      return;
    }
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      setError(translate("canvasCreateFailed"));
      return;
    }
    setError(null);
    ctx.drawImage(video, 0, 0, w, h);
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, "image/jpeg", 0.9),
    );
    if (!blob) {
      setError(translate("cameraEncodeFailed"));
      return;
    }
    // Release the camera right after the shot; 重拍 re-acquires the stream.
    stopTracks();
    let url: string;
    try {
      url = await blobToDataUrl(blob);
    } catch (e) {
      if (mountedRef.current) setError(e instanceof Error ? e.message : String(e));
      setPhase("idle");
      return;
    }
    if (!mountedRef.current) return;
    setDataUrl(url);
    setPhase("captured");
    setSavePending(true);
    const name = `camera_${stamp()}`;
    const result = await execTool("image_save", {
      base64: url.slice(url.indexOf(",") + 1),
      filename: name,
      display_name: `${t("cameraPhoto")} ${stamp()}`,
    });
    if (!mountedRef.current) return;
    setSavePending(false);
    showResult(result.result);
  };

  const retake = () => {
    setDataUrl(null);
    void start(facing);
  };

  if (options.error) {
    return (
      <div className="camera-card">
        <div className="chart-head">
          <span className="chart-badge">{t("camera")}</span>
          <span className="chart-error-text">{options.error}</span>
        </div>
      </div>
    );
  }

  return (
    <div className="camera-card">
      <div className="chart-head">
        <span className="chart-badge">{t("camera")}</span>
        <span className="camera-facing">
          {phase === "live" ? (facing === "environment" ? translate("liveRear") : translate("liveFront")) : translate("liveView")}
        </span>
      </div>
      {phase === "live" ? (
        <video ref={videoRef} className="camera-video" autoPlay playsInline muted />
      ) : phase === "captured" && dataUrl ? (
        <img className="camera-shot" src={dataUrl} alt={t("capturedPhoto")} />
      ) : (
        <div className="camera-idle">{error ? translate("cameraNotStarted") : translate("cameraIdle")}</div>
      )}
      <div className="camera-actions">
        {phase === "live" ? (
          <>
            <button type="button" className="camera-shoot" onClick={() => void shoot()}>
              {t("takePhoto")}
            </button>
            <button
              type="button"
              onClick={() => void start(facing === "environment" ? "user" : "environment")}
            >
              {t("switchCamera")}{facing === "environment" ? translate("front") : translate("rear")}
            </button>
            <button
              type="button"
              onClick={() => {
                stopTracks();
                setPhase("idle");
              }}
            >
              {t("close")}
            </button>
          </>
        ) : phase === "captured" ? (
          <>
            <button type="button" className="camera-shoot" onClick={retake}>
              {t("retake")}
            </button>
          </>
        ) : (
          <button type="button" className="camera-shoot" onClick={() => void start(facing)}>
            {t("startCamera")}
          </button>
        )}
      </div>
      {savePending && <div className="camera-save">{t("savingPhoto")}</div>}
      {error && (
        <div className="camera-error">
          <span>{error}</span>
          {permDenied && (
            <button type="button" onClick={() => openAppSettings()}>
              {t("openSystemSettings")}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

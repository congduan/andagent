import { translate, useI18n } from "./i18n";
import { useEffect, useMemo, useRef, useState } from "react";
import { Icon } from "./icons";

/**
 * ```shader fence: live WebGL2 fragment shader (Shadertoy-style).
 *
 * Payload is either raw GLSL ES 3.00 fragment source or JSON:
 *   { "title": "...", "fragment": "...", "paused": false }
 * Uniforms: iResolution (vec2), iTime (float seconds), iFrame (int).
 * The fragment source must declare `out vec4 fragColor;`.
 */
type ShaderSpec = { fragment: string; title: string; paused: boolean };

const VERTEX = `#version 300 es
in vec2 aPos;
void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }`;

const FALLBACK = `#version 300 es
precision highp float;
uniform vec2 iResolution;
uniform float iTime;
out vec4 fragColor;
void main(){
  vec2 uv = gl_FragCoord.xy / iResolution;
  fragColor = vec4(uv, 0.5 + 0.5 * sin(iTime), 1.0);
}`;

function parseSpec(code: string): ShaderSpec {
  const trimmed = code.trim();
  if (trimmed.startsWith("{")) {
    try {
      const raw = JSON.parse(trimmed) as Record<string, unknown>;
      return {
        fragment: typeof raw.fragment === "string" ? raw.fragment : FALLBACK,
        title: typeof raw.title === "string" ? raw.title : "Shader",
        paused: raw.paused === true,
      };
    } catch {
      return { fragment: FALLBACK, title: "Shader", paused: false };
    }
  }
  return { fragment: trimmed || FALLBACK, title: "Shader", paused: false };
}

function compile(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader {
  const shader = gl.createShader(type);
  if (!shader) throw new Error(translate("shaderCreateFailed"));
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(shader) ?? translate("shaderUnknownError");
    gl.deleteShader(shader);
    throw new Error(log);
  }
  return shader;
}

export function ShaderCard({ code }: { code: string }) {
  const { t } = useI18n();
  const spec = useMemo(() => parseSpec(code), [code]);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [error, setError] = useState("");
  const [paused, setPaused] = useState(spec.paused);
  const [fullscreen, setFullscreen] = useState(false);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const gl = canvas.getContext("webgl2", { antialias: false, alpha: false });
    if (!gl) {
      setError(translate("webglUnsupported"));
      return;
    }
    setError("");
    let program: WebGLProgram | null = null;
    let raf = 0;
    try {
      const vs = compile(gl, gl.VERTEX_SHADER, VERTEX);
      const fs = compile(gl, gl.FRAGMENT_SHADER, spec.fragment);
      program = gl.createProgram();
      if (!program) throw new Error(translate("programCreateFailed"));
      gl.attachShader(program, vs);
      gl.attachShader(program, fs);
      gl.linkProgram(program);
      gl.deleteShader(vs);
      gl.deleteShader(fs);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
        throw new Error(gl.getProgramInfoLog(program) ?? translate("shaderLinkFailed"));
      }
      const buffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
      const loc = gl.getAttribLocation(program, "aPos");
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
      gl.useProgram(program);
      const uResolution = gl.getUniformLocation(program, "iResolution");
      const uTime = gl.getUniformLocation(program, "iTime");
      const uFrame = gl.getUniformLocation(program, "iFrame");
      const start = performance.now();
      let frame = 0;
      const draw = () => {
        const scale = Math.min(window.devicePixelRatio || 1, 2);
        const w = Math.max(1, Math.round(canvas.clientWidth * scale));
        const h = Math.max(1, Math.round(canvas.clientHeight * scale));
        if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
        gl.viewport(0, 0, w, h);
        gl.uniform2f(uResolution, w, h);
        gl.uniform1f(uTime, (performance.now() - start) / 1000);
        gl.uniform1i(uFrame, frame++);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        if (!paused) raf = requestAnimationFrame(draw);
      };
      draw();
      return () => {
        cancelAnimationFrame(raf);
        if (program) gl.deleteProgram(program);
        if (buffer) gl.deleteBuffer(buffer);
        gl.getExtension("WEBGL_lose_context")?.loseContext();
      };
    } catch (e) {
      setError(`${t("shaderCompileFailed")}：${String(e)}`);
      return () => { if (program) gl.deleteProgram(program); };
    }
  }, [spec, paused, fullscreen]);

  return (
    <div className={`shader-card${fullscreen ? " fullscreen" : ""}`}>
      <div className="webview-bar">
        <span className="webview-uri"><Icon name="sparkles" size={13} /> {spec.title}</span>
        <button type="button" onClick={() => setPaused((v) => !v)}>{paused ? t("resume") : t("pause")}</button>
        <button type="button" onClick={() => setFullscreen((v) => !v)}>{fullscreen ? t("exitFullscreen") : t("fullscreen")}</button>
      </div>
      <canvas ref={canvasRef} aria-label={spec.title} />
      {error && <div className="rich-error">{error}</div>}
    </div>
  );
}

import { useEffect, useRef, useState, type FormEvent, type ReactElement } from "react";
import type { BuildImportRequest, BuildImportSource, BuildPlan } from "@mc/protocol";
import { FileBox, Upload, X } from "lucide-react";
import { importBuildFile } from "./api.js";

interface BuildImportDialogProps {
  open: boolean;
  onClose(): void;
  onImported(plan: BuildPlan): void;
}

const SOURCE_LABELS: Record<BuildImportSource, string> = {
  json: "JSON",
  schem: "Sponge .schem",
  litematic: "Litematica",
  "pixel-art": "像素画",
  "reference-image": "参考图",
};

function inferSource(fileName: string): BuildImportSource {
  const lower = fileName.toLowerCase();
  if (lower.endsWith(".schem")) return "schem";
  if (lower.endsWith(".litematic")) return "litematic";
  if (lower.endsWith(".png")) return "reference-image";
  return "json";
}

function fileBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error("读取文件失败"));
    reader.onload = () => {
      const result = reader.result;
      if (typeof result !== "string") {
        reject(new Error("读取文件失败"));
        return;
      }
      const separator = result.indexOf(",");
      resolve(separator >= 0 ? result.slice(separator + 1) : result);
    };
    reader.readAsDataURL(file);
  });
}

export function BuildImportDialog({ open, onClose, onImported }: BuildImportDialogProps): ReactElement | null {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState("");
  const [source, setSource] = useState<BuildImportSource>("json");
  const [origin, setOrigin] = useState({ x: "0", y: "64", z: "0" });
  const [plane, setPlane] = useState<"xy" | "xz">("xy");
  const [maxWidth, setMaxWidth] = useState("128");
  const [maxHeight, setMaxHeight] = useState("128");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (open) setError("");
  }, [open]);

  if (!open) return null;

  const selectFile = (selected: File | undefined) => {
    if (!selected) return;
    setFile(selected);
    const inferred = inferSource(selected.name);
    setSource(inferred);
    setName(selected.name.replace(/\.[^.]+$/u, ""));
    setError("");
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!file) {
      setError("请选择建筑文件");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const request: BuildImportRequest = {
        name: name.trim() || file.name,
        source,
        origin: { x: Number(origin.x), y: Number(origin.y), z: Number(origin.z) },
        fileName: file.name,
        dataBase64: await fileBase64(file),
        includeAir: false,
        image: {
          plane,
          maxWidth: Number(maxWidth),
          maxHeight: Number(maxHeight),
          alphaThreshold: 16,
        },
      };
      const plan = await importBuildFile(request);
      onImported(plan);
      onClose();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setBusy(false);
    }
  };

  const imageSource = source === "pixel-art" || source === "reference-image";
  return (
    <div className="ai-dialog-backdrop build-dialog-backdrop" role="presentation">
      <section className="build-dialog" role="dialog" aria-modal="true" aria-labelledby="build-import-title">
        <header className="build-dialog-header">
          <div>
            <FileBox size={19} />
            <h2 id="build-import-title">导入建筑</h2>
          </div>
          <button className="dialog-close" type="button" title="关闭" onClick={onClose}><X size={18} /></button>
        </header>
        <form className="build-import-form" onSubmit={(event) => void submit(event)}>
          <input
            ref={inputRef}
            className="visually-hidden"
            type="file"
            accept=".json,.schem,.litematic,.png,application/json,image/png"
            onChange={(event) => selectFile(event.target.files?.[0])}
          />
          <button className="build-file-picker" type="button" onClick={() => inputRef.current?.click()}>
            <Upload size={19} />
            <span>{file?.name ?? "选择文件"}</span>
          </button>

          <label className="provider-field">
            <span>名称</span>
            <input value={name} maxLength={120} onChange={(event) => setName(event.target.value)} />
          </label>
          <label className="provider-field">
            <span>来源</span>
            <select value={source} onChange={(event) => setSource(event.target.value as BuildImportSource)}>
              {(Object.entries(SOURCE_LABELS) as Array<[BuildImportSource, string]>).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>

          <fieldset className="coordinate-fields">
            <legend>起点坐标</legend>
            {(["x", "y", "z"] as const).map((axis) => (
              <label key={axis}><span>{axis.toUpperCase()}</span><input type="number" value={origin[axis]} onChange={(event) => setOrigin((current) => ({ ...current, [axis]: event.target.value }))} /></label>
            ))}
          </fieldset>

          {imageSource && (
            <div className="image-import-options">
              <fieldset className="plane-control">
                <legend>平面</legend>
                <div>
                  <button type="button" className={plane === "xy" ? "is-active" : ""} onClick={() => setPlane("xy")}>立面 XY</button>
                  <button type="button" className={plane === "xz" ? "is-active" : ""} onClick={() => setPlane("xz")}>地面 XZ</button>
                </div>
              </fieldset>
              <div className="image-size-fields">
                <label><span>最大宽度</span><input type="number" min="1" max="512" value={maxWidth} onChange={(event) => setMaxWidth(event.target.value)} /></label>
                <label><span>最大高度</span><input type="number" min="1" max="512" value={maxHeight} onChange={(event) => setMaxHeight(event.target.value)} /></label>
              </div>
            </div>
          )}

          {error && <div className="build-import-error">{error}</div>}
          <footer className="provider-form-actions">
            <button className="secondary-command" type="button" onClick={onClose}>取消</button>
            <button className="primary-command" type="submit" disabled={busy || !file}><Upload size={16} />{busy ? "正在导入" : "生成预览"}</button>
          </footer>
        </form>
      </section>
    </div>
  );
}

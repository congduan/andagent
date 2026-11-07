import { Icon } from "./icons";

type Props = {
  /** 标题文案（调用方负责 i18n）。 */
  title: string;
  /** 返回按钮的 aria-label（首页是「关闭」，子页是「返回」）。 */
  backLabel: string;
  onBack: () => void;
  /** 需要 aria-labelledby 时给标题挂 id。 */
  id?: string;
};

/**
 * 设置首页、设置子页、模型向导共用的顶栏模板。
 * 结构只有「← 返回 + 标题」，高度由 CSS 固定，避免各页切换时标题栏跳动。
 */
export function SettingsHead({ title, backLabel, onBack, id }: Props) {
  return (
    <header className="settings-head">
      <button type="button" className="icon-btn" onClick={onBack} aria-label={backLabel}>
        <Icon name="arrow-left" size={19} />
      </button>
      <h2 id={id}>{title}</h2>
    </header>
  );
}

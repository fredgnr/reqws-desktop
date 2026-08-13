import { Copy } from 'lucide-react';
import type { DisplayError } from '../error-utils';

export function ErrorNotice({ error }: { error: DisplayError }): React.JSX.Element {
  const log = [
    `[${error.code}] ${error.message}`,
    error.repositoryName ? `Repository: ${error.repositoryName}` : '',
    error.stage ? `Stage: ${error.stage}` : '',
    error.detail ?? '',
  ].filter(Boolean).join('\n');

  return (
    <div className="notice error" role="alert">
      <div className="copy-row">
        <div>
          <strong>{error.code}</strong> · {error.message}
          {error.repositoryName && <div>Repository：{error.repositoryName}</div>}
          {error.stage && <div>阶段：{error.stage}</div>}
          {error.detail && <pre className="error-detail">{error.detail}</pre>}
        </div>
        <button
          aria-label="复制错误日志"
          className="button small icon-only"
          onClick={() => void navigator.clipboard?.writeText(log)}
          title="复制错误日志"
          type="button"
        >
          <Copy aria-hidden="true" size={14} />
        </button>
      </div>
    </div>
  );
}

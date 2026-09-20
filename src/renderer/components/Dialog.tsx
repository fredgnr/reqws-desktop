import { useEffect, useRef, type MouseEvent, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { useTranslation } from 'react-i18next';

interface DialogProps {
  children: ReactNode;
  titleId: string;
  onClose?: () => void;
  className?: string;
  drawer?: boolean;
  dismissible?: boolean;
}

const focusableSelector = [
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  'summary:not([aria-disabled="true"])',
  '[href]',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

function visibleFocusables(container: HTMLElement): HTMLElement[] {
  return [...container.querySelectorAll<HTMLElement>(focusableSelector)].filter((element) => {
    if (element.tabIndex < 0 || element.closest('[hidden], [aria-hidden="true"]')) return false;
    for (let parent = element.parentElement; parent && parent !== container; parent = parent.parentElement) {
      if (parent instanceof HTMLDetailsElement && !parent.open && parent.querySelector(':scope > summary') !== element) return false;
    }
    return true;
  });
}

export function Dialog({
  children,
  titleId,
  onClose,
  className = '',
  drawer = false,
  dismissible = true,
}: DialogProps): React.JSX.Element {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const container = containerRef.current;
    const focusables = container ? visibleFocusables(container) : [];
    (focusables?.[0] ?? container)?.focus();

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape' && dismissible && onClose) {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== 'Tab' || !container) return;
      const current = visibleFocusables(container);
      if (current.length === 0) {
        event.preventDefault();
        container.focus();
        return;
      }
      const first = current[0];
      const last = current[current.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      previouslyFocused?.focus();
    };
  }, [dismissible, onClose]);

  const handleBackdrop = (event: MouseEvent<HTMLDivElement>): void => {
    if (dismissible && onClose && event.currentTarget === event.target) onClose();
  };

  return (
    <div
      className={`overlay ${drawer ? 'drawer-overlay' : ''}`}
      onMouseDown={handleBackdrop}
      role="presentation"
    >
      <div
        aria-labelledby={titleId}
        aria-modal="true"
        className={`${drawer ? 'drawer' : 'dialog'} ${className}`}
        ref={containerRef}
        role="dialog"
        tabIndex={-1}
      >
        {children}
      </div>
    </div>
  );
}

export function CloseButton({ onClick, label }: { onClick: () => void; label?: string }): React.JSX.Element {
  const { t } = useTranslation();

  return (
    <button aria-label={label ?? t('common.close')} className="close-button" onClick={onClick} type="button">
      <X aria-hidden="true" size={17} />
    </button>
  );
}

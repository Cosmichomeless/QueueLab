import type { JobStatus } from "@/lib/api";
import { STATUS_LABELS } from "@/lib/format";
import styles from "./StatusBadge.module.css";

export function StatusBadge({ status }: { status: JobStatus }) {
  return (
    <span className={`${styles.badge} ${styles[status.toLowerCase()]}`} data-status={status}>
      {STATUS_LABELS[status] ?? status}
    </span>
  );
}

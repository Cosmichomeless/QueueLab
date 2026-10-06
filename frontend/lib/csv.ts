/**
 * Validación del CSV antes de enviarlo. Replica las reglas de la API (backend/README.md, «Subida de CSV») para
 * avisar sin gastar una subida; la API sigue siendo quien decide y sus errores también se muestran.
 */

/** Igual que `queuelab.csv.max-file-size` de la API (10 MiB). */
export const MAX_CSV_BYTES = 10 * 1024 * 1024;

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

/** Devuelve el motivo por el que el fichero no se puede enviar, o `null` si es válido. */
export function validateCsvFile(file: File | null | undefined): string | null {
  if (!file) return "Selecciona un fichero CSV.";
  const declaredAsCsv = file.type === "text/csv" || file.name.toLowerCase().endsWith(".csv");
  if (!declaredAsCsv) return "El fichero debe ser un CSV (extensión .csv o tipo text/csv).";
  if (file.size === 0) return "El fichero está vacío.";
  if (file.size > MAX_CSV_BYTES) {
    return `El fichero pesa ${formatBytes(file.size)} y el máximo es ${formatBytes(MAX_CSV_BYTES)}.`;
  }
  return null;
}

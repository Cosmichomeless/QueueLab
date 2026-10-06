import { describe, expect, it } from "vitest";
import { csvFile } from "@/test/fixtures";
import { MAX_CSV_BYTES, validateCsvFile } from "./csv";

describe("validateCsvFile", () => {
  it("pide un fichero cuando no hay ninguno", () => {
    expect(validateCsvFile(null)).toBe("Selecciona un fichero CSV.");
    expect(validateCsvFile(undefined)).toBe("Selecciona un fichero CSV.");
  });

  it("acepta un CSV por tipo o por extensión", () => {
    expect(validateCsvFile(csvFile())).toBeNull();
    expect(validateCsvFile(csvFile("a\n1\n", "DATOS.CSV", ""))).toBeNull();
    expect(validateCsvFile(csvFile("a\n1\n", "datos.txt", "text/csv"))).toBeNull();
  });

  it("rechaza lo que no es un CSV", () => {
    expect(validateCsvFile(csvFile("{}", "datos.json", "application/json"))).toBe(
      "El fichero debe ser un CSV (extensión .csv o tipo text/csv).",
    );
  });

  it("rechaza un fichero vacío", () => {
    expect(validateCsvFile(csvFile(""))).toBe("El fichero está vacío.");
  });

  it("rechaza un fichero por encima del máximo y acepta el límite exacto", () => {
    expect(validateCsvFile(csvFile("x".repeat(MAX_CSV_BYTES + 1)))).toBe(
      "El fichero pesa 10.0 MiB y el máximo es 10.0 MiB.",
    );
    expect(validateCsvFile(csvFile("x".repeat(MAX_CSV_BYTES)))).toBeNull();
  });
});

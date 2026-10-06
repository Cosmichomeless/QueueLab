import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Job } from "@/lib/api";
import { JOB_ID, jsonResponse, makeJob, problem } from "@/test/fixtures";
import { JobDetail } from "./JobDetail";

const fetchMock = vi.fn<typeof fetch>();
const JOB_URL = `http://localhost:8080/api/v1/jobs/${JOB_ID}`;

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

/** Respuestas de GET /jobs/{id} en orden; la última se repite. */
function serveJob(...responses: Array<Job | Response>) {
  let index = 0;
  fetchMock.mockImplementation(async (url, init) => {
    if (String(url) !== JOB_URL || (init?.method ?? "GET") !== "GET") throw new Error(`Petición inesperada: ${url}`);
    const next = responses[Math.min(index++, responses.length - 1)];
    return next instanceof Response ? next.clone() : jsonResponse(next);
  });
}

const gets = () => fetchMock.mock.calls.filter(([, init]) => (init?.method ?? "GET") === "GET").length;

async function renderDetail() {
  render(<JobDetail id={JOB_ID} />);
  await act(async () => {}); // resuelve la primera carga
}

// user-event espera con setTimeout y se cuelga con los timers falsos de Vitest: aquí basta con fireEvent.
function click(element: HTMLElement) {
  fireEvent.click(element);
}

async function tick(ms = 2000) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe("progreso", () => {
  it("muestra «Cargando…» hasta tener respuesta", () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    render(<JobDetail id={JOB_ID} />);
    expect(screen.getByText("Cargando trabajo…")).toBeInTheDocument();
  });

  it.each([
    ["QUEUED", "En cola", "Esperando en la cola a que un worker lo recoja."],
    ["RUNNING", "En ejecución", "Un worker lo está procesando."],
    ["RETRYING", "Reintentando", "El intento anterior falló; se volverá a ejecutar."],
  ] as const)("%s: estado y mensaje de progreso", async (status, label, message) => {
    serveJob(makeJob({ status, attempts: 1, startedAt: "2026-10-06T10:00:05Z" }));
    await renderDetail();
    expect(screen.getByText(label)).toBeInTheDocument();
    expect(screen.getByText(`${message} Se actualiza solo.`)).toBeInTheDocument();
    expect(screen.getByText("Intentos").nextElementSibling).toHaveTextContent("1");
  });

  it("sigue el trabajo hasta que termina y entonces deja de consultar", async () => {
    serveJob(
      makeJob({ status: "QUEUED" }),
      makeJob({ status: "RUNNING", startedAt: "2026-10-06T10:00:05Z", attempts: 1 }),
      makeJob({
        status: "COMPLETED",
        attempts: 1,
        startedAt: "2026-10-06T10:00:05Z",
        finishedAt: "2026-10-06T10:00:07Z",
        result: "3 filas procesadas",
        resultFile: { name: "resultado.csv", contentType: "text/csv", size: 2048, downloadUrl: `/api/v1/jobs/${JOB_ID}/result` },
      }),
    );
    await renderDetail();
    expect(screen.getByText("En cola")).toBeInTheDocument();

    await tick();
    expect(screen.getByText("En ejecución")).toBeInTheDocument();

    await tick();
    expect(screen.getByText("Completado")).toBeInTheDocument();
    expect(screen.getByText("El trabajo ha terminado correctamente.")).toBeInTheDocument();

    const calls = gets();
    await tick(10_000);
    expect(gets()).toBe(calls);
  });
});

describe("resultado", () => {
  it("un trabajo completado muestra el resultado y el enlace de descarga", async () => {
    serveJob(
      makeJob({
        status: "COMPLETED",
        attempts: 1,
        result: "3 filas procesadas",
        resultFile: { name: "resultado.csv", contentType: "text/csv", size: 2048, downloadUrl: `/api/v1/jobs/${JOB_ID}/result` },
      }),
    );
    await renderDetail();
    expect(screen.getByRole("heading", { name: "Resultado" })).toBeInTheDocument();
    expect(screen.getByText("3 filas procesadas")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "Descargar resultado.csv" });
    expect(link).toHaveAttribute("href", `http://localhost:8080/api/v1/jobs/${JOB_ID}/result`);
    expect(link).toHaveAttribute("download", "resultado.csv");
    expect(screen.getByText("(text/csv, 2.0 KiB)")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar trabajo" })).not.toBeInTheDocument();
  });

  it("un trabajo completado sin fichero no ofrece descarga", async () => {
    serveJob(makeJob({ status: "COMPLETED", result: "ok" }));
    await renderDetail();
    expect(screen.queryByRole("link", { name: /Descargar/ })).not.toBeInTheDocument();
  });
});

describe("error del trabajo", () => {
  it("un trabajo fallido muestra el error y la opción de reintentar", async () => {
    serveJob(makeJob({ status: "FAILED", attempts: 3, error: "Fila 2: columnas inesperadas" }));
    await renderDetail();
    expect(screen.getByText("Fallido")).toBeInTheDocument();
    expect(screen.getByText("El trabajo ha fallado.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Fallo" })).toBeInTheDocument();
    expect(screen.getByText("Fila 2: columnas inesperadas")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reintentar trabajo" })).toBeEnabled();
    const calls = gets();
    await tick(10_000);
    expect(gets()).toBe(calls);
  });

  it("un trabajo reintentándose enseña su último fallo, sin botón de reintento", async () => {
    serveJob(makeJob({ status: "RETRYING", attempts: 1, error: "Fichero de entrada no disponible" }));
    await renderDetail();
    expect(screen.getByRole("heading", { name: "Último fallo" })).toBeInTheDocument();
    expect(screen.getByText("Fichero de entrada no disponible")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar trabajo" })).not.toBeInTheDocument();
  });

  it("un trabajo en cola no muestra sección de fallo ni de resultado", async () => {
    serveJob(makeJob());
    await renderDetail();
    expect(screen.queryByRole("heading", { name: /Fallo|Resultado/ })).not.toBeInTheDocument();
  });
});

describe("errores de carga y de refresco", () => {
  it("un identificador inexistente (404) se muestra como «no existe», sin reintentar", async () => {
    serveJob(problem(404, "Not Found", "Job no encontrado"));
    await renderDetail();
    expect(screen.getByRole("alert")).toHaveTextContent("No existe ningún trabajo con ese identificador.");
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
    const calls = gets();
    await tick(10_000);
    expect(gets()).toBe(calls);
  });

  it("un identificador que no es UUID (400) también es «no existe»", async () => {
    serveJob(problem(400, "Bad Request", "Identificador inválido"));
    await renderDetail();
    expect(screen.getByRole("alert")).toHaveTextContent("No existe ningún trabajo con ese identificador.");
  });

  it("un fallo en la primera carga ofrece reintentar a mano", async () => {
    serveJob(problem(500, "Internal Server Error", "Base de datos caída"), makeJob({ status: "FAILED", error: "x" }));
    await renderDetail();
    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo cargar el trabajo: Base de datos caída");

    click(screen.getByRole("button", { name: "Reintentar" }));
    await act(async () => {});
    expect(screen.getByText("Fallido")).toBeInTheDocument();
    expect(screen.queryByText(/No se pudo cargar/)).not.toBeInTheDocument();
  });

  it("un fallo transitorio al refrescar conserva el estado, avisa y se recupera", async () => {
    serveJob(
      makeJob({ status: "RUNNING", attempts: 1 }),
      problem(503, "Service Unavailable", "API reiniciándose"),
      makeJob({ status: "COMPLETED", attempts: 1, result: "listo" }),
    );
    await renderDetail();
    expect(screen.getByText("En ejecución")).toBeInTheDocument();

    await tick();
    expect(screen.getByText("En ejecución")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo actualizar el estado (API reiniciándose)");

    await tick();
    expect(screen.getByText("Completado")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("deja de consultar al desmontar", async () => {
    serveJob(makeJob({ status: "RUNNING" }));
    render(<JobDetail id={JOB_ID} />);
    await act(async () => {});
    const calls = gets();
    cleanup();
    await tick(10_000);
    expect(gets()).toBe(calls);
  });
});

describe("reintento de un trabajo fallido", () => {
  const failed = () => makeJob({ status: "FAILED", attempts: 3, error: "Fila 2: columnas inesperadas" });

  /** GET /jobs/{id} sirve `states` en orden (la última se repite); POST /retry devuelve `retryResponse`. */
  function serveWithRetry(states: Job[], retryResponse: Response | Promise<Response>) {
    let index = 0;
    fetchMock.mockImplementation(async (url, init) => {
      if ((init?.method ?? "GET") === "POST") {
        expect(String(url)).toBe(`${JOB_URL}/retry`);
        return (await retryResponse).clone();
      }
      return jsonResponse(states[Math.min(index++, states.length - 1)]);
    });
  }

  const posts = () => fetchMock.mock.calls.filter(([, init]) => init?.method === "POST").length;

  it("lo devuelve a la cola, avisa y reanuda el seguimiento", async () => {
    const queued = makeJob({ status: "QUEUED", attempts: 0 });
    // Tras el POST se vuelve a pedir el trabajo de inmediato (GET 2º: sigue en cola), y después cada 2 s.
    serveWithRetry(
      [failed(), queued, makeJob({ status: "RUNNING", attempts: 1 }), makeJob({ status: "COMPLETED", attempts: 1, result: "ok" })],
      jsonResponse(queued, 202),
    );
    await renderDetail();

    click(screen.getByRole("button", { name: "Reintentar trabajo" }));
    await act(async () => {});

    expect(posts()).toBe(1);
    expect(screen.getByText("En cola")).toBeInTheDocument();
    expect(screen.getByText("Intentos").nextElementSibling).toHaveTextContent("0");
    expect(screen.getByText(/Reintento solicitado/)).toBeInTheDocument();
    expect(screen.queryByText("Fila 2: columnas inesperadas")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar trabajo" })).not.toBeInTheDocument();

    await tick();
    expect(screen.getByText("En ejecución")).toBeInTheDocument();
    await tick();
    expect(screen.getByText("Completado")).toBeInTheDocument();
  });

  it("muestra «Reintentando…», deshabilita el botón y no duplica la petición", async () => {
    serveWithRetry([failed()], new Promise<Response>(() => {}));
    await renderDetail();

    click(screen.getByRole("button", { name: "Reintentar trabajo" }));
    const sending = screen.getByRole("button", { name: "Reintentando…" });
    expect(sending).toBeDisabled();
    click(sending);
    expect(posts()).toBe(1);
  });

  it("si la API responde 409, explica el motivo y vuelve a mostrar el estado real", async () => {
    serveWithRetry(
      [failed(), makeJob({ status: "RUNNING", attempts: 1 })],
      problem(409, "Conflict", "Solo se pueden reintentar trabajos en FAILED; el trabajo x está en RUNNING"),
    );
    await renderDetail();

    click(screen.getByRole("button", { name: "Reintentar trabajo" }));
    await act(async () => {});

    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo reintentar: Solo se pueden reintentar trabajos en FAILED");
    expect(screen.getByText("En ejecución")).toBeInTheDocument();
  });

  it("si falla la red, avisa y conserva el botón para volver a intentarlo", async () => {
    let post = 0;
    fetchMock.mockImplementation(async (_url, init) => {
      if (init?.method === "POST") {
        post++;
        if (post === 1) throw new TypeError("Failed to fetch");
        return jsonResponse(makeJob(), 202);
      }
      return jsonResponse(failed());
    });
    await renderDetail();

    click(screen.getByRole("button", { name: "Reintentar trabajo" }));
    await act(async () => {});
    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo reintentar: No se pudo conectar con la API");
    expect(screen.getByRole("button", { name: "Reintentar trabajo" })).toBeEnabled();
  });
});

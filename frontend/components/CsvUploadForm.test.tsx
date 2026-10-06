import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { csvFile, jsonResponse, makeJob, problem, JOB_ID } from "@/test/fixtures";
import { CsvUploadForm } from "./CsvUploadForm";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  fetchMock.mockReset();
  push.mockReset();
  vi.unstubAllGlobals();
});

// `applyAccept: false`: el input declara accept=".csv,text/csv" y user-event, por defecto, descartaría los ficheros
// que no encajan; así se puede comprobar también la validación propia del formulario.
function setup() {
  const user = userEvent.setup({ applyAccept: false });
  render(<CsvUploadForm />);
  const input = screen.getByLabelText("Fichero CSV");
  const submit = screen.getByRole("button", { name: "Enviar" });
  return { user, input, submit };
}

describe("validación", () => {
  it("no muestra errores al abrir la página", () => {
    setup();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("pide un fichero si se envía sin elegir ninguno, sin llamar a la API", async () => {
    const { user, submit } = setup();
    await user.click(submit);
    expect(await screen.findByRole("alert")).toHaveTextContent("Selecciona un fichero CSV.");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rechaza un fichero que no es CSV y deshabilita el envío", async () => {
    const { user, input, submit } = setup();
    await user.upload(input, csvFile("{}", "datos.json", "application/json"));
    expect(await screen.findByRole("alert")).toHaveTextContent("El fichero debe ser un CSV");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(submit).toBeDisabled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rechaza un fichero vacío", async () => {
    const { user, input, submit } = setup();
    await user.upload(input, csvFile(""));
    expect(await screen.findByRole("alert")).toHaveTextContent("El fichero está vacío.");
    expect(submit).toBeDisabled();
  });

  it("rechaza un fichero demasiado grande indicando su tamaño y el máximo", async () => {
    const { user, input, submit } = setup();
    const big = csvFile("x");
    Object.defineProperty(big, "size", { value: 11 * 1024 * 1024 });
    await user.upload(input, big);
    expect(await screen.findByRole("alert")).toHaveTextContent("El fichero pesa 11.0 MiB y el máximo es 10.0 MiB.");
    expect(submit).toBeDisabled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("el error desaparece al elegir un fichero válido", async () => {
    const { user, input, submit } = setup();
    await user.upload(input, csvFile(""));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    await user.upload(input, csvFile());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(submit).toBeEnabled();
    expect(screen.getByText(/datos\.csv/)).toBeInTheDocument();
  });
});

describe("subida fallida", () => {
  it.each([
    [413, "Payload Too Large", "El fichero supera el máximo de 10 MiB."],
    [400, "Bad Request", "El CSV no tiene cabecera."],
  ])("muestra el detalle de la API (%s) y deja reintentar", async (status, title, detail) => {
    fetchMock.mockResolvedValue(problem(status, title, detail));
    const { user, input, submit } = setup();
    await user.upload(input, csvFile());
    await user.click(submit);

    expect(await screen.findByRole("alert")).toHaveTextContent(`No se pudo enviar el fichero: ${detail}`);
    expect(push).not.toHaveBeenCalled();
    // El fichero se conserva y el botón vuelve a estar disponible.
    expect(screen.getByRole("button", { name: "Enviar" })).toBeEnabled();
    expect(input).toBeEnabled();
    expect(screen.getByText(/datos\.csv/)).toBeInTheDocument();
  });

  it("explica el fallo de red", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    const { user, input, submit } = setup();
    await user.upload(input, csvFile());
    await user.click(submit);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "No se pudo enviar el fichero: No se pudo conectar con la API. Comprueba que está en marcha.",
    );
    expect(push).not.toHaveBeenCalled();
  });

  it("permite volver a enviar tras un fallo y limpia el error", async () => {
    fetchMock.mockResolvedValueOnce(problem(503, "Service Unavailable", "Cola no disponible."));
    fetchMock.mockResolvedValueOnce(jsonResponse(makeJob(), 201));
    const { user, input, submit } = setup();
    await user.upload(input, csvFile());
    await user.click(submit);
    expect(await screen.findByRole("alert")).toHaveTextContent("Cola no disponible.");

    await user.click(submit);
    await waitFor(() => expect(push).toHaveBeenCalledWith(`/jobs/${JOB_ID}`));
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("subida correcta", () => {
  it("envía el fichero y navega al detalle del trabajo creado", async () => {
    fetchMock.mockResolvedValue(jsonResponse(makeJob(), 201));
    const { user, input, submit } = setup();
    await user.upload(input, csvFile("a,b\n1,2\n", "ventas.csv"));
    await user.click(submit);

    await waitFor(() => expect(push).toHaveBeenCalledWith(`/jobs/${JOB_ID}`));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const body = fetchMock.mock.calls[0][1]!.body as FormData;
    expect((body.get("file") as File).name).toBe("ventas.csv");
  });

  it("muestra «Enviando…», bloquea el formulario y no duplica el envío", async () => {
    let resolve!: (response: Response) => void;
    fetchMock.mockReturnValue(new Promise<Response>((r) => (resolve = r)));
    const { user, input, submit } = setup();
    await user.upload(input, csvFile());
    await user.click(submit);

    const sending = await screen.findByRole("button", { name: "Enviando…" });
    expect(sending).toBeDisabled();
    expect(input).toBeDisabled();
    await user.click(sending);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    resolve(jsonResponse(makeJob(), 201));
    await waitFor(() => expect(push).toHaveBeenCalledWith(`/jobs/${JOB_ID}`));
  });
});

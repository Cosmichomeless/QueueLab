import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { csvFile, jsonResponse, makeJob, problem } from "@/test/fixtures";
import { ApiError, downloadHref, getJob, listJobs, retryJob, uploadCsv } from "./api";

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

function lastCall() {
  const [url, init] = fetchMock.mock.calls.at(-1)!;
  return { url: String(url), init: init as RequestInit };
}

describe("errores de la API", () => {
  it("usa el detail del problem+json", async () => {
    fetchMock.mockResolvedValue(problem(413, "Payload Too Large", "El fichero supera los 10 MiB."));
    await expect(getJob("x")).rejects.toMatchObject({ message: "El fichero supera los 10 MiB.", status: 413 });
  });

  it("cae al title cuando no hay detail", async () => {
    fetchMock.mockResolvedValue(problem(404, "Not Found"));
    await expect(getJob("x")).rejects.toMatchObject({ message: "Not Found", status: 404 });
  });

  it("cae a «Error N» cuando el cuerpo no es JSON", async () => {
    fetchMock.mockResolvedValue(new Response("<html>boom</html>", { status: 502 }));
    await expect(getJob("x")).rejects.toMatchObject({ message: "Error 502", status: 502 });
  });

  it("convierte un fallo de red en un ApiError sin estado", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    const error = await getJob("x").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ message: "No se pudo conectar con la API. Comprueba que está en marcha.", status: null });
  });
});

describe("uploadCsv", () => {
  it("envía el fichero como multipart en la parte «file», sin fijar Content-Type", async () => {
    const job = makeJob();
    fetchMock.mockResolvedValue(jsonResponse(job, 201));

    await expect(uploadCsv(csvFile("a,b\n1,2\n", "ventas.csv"))).resolves.toEqual(job);

    const { url, init } = lastCall();
    expect(url).toBe("http://localhost:8080/api/v1/jobs/csv");
    expect(init.method).toBe("POST");
    expect(init.headers).toBeUndefined();
    const sent = (init.body as FormData).get("file") as File;
    expect(sent.name).toBe("ventas.csv");
    expect(await sent.text()).toBe("a,b\n1,2\n");
  });
});

describe("listJobs, getJob, retryJob y downloadHref", () => {
  it("listJobs construye la query con límite, cursor y estado", async () => {
    fetchMock.mockImplementation(async () => jsonResponse({ items: [], nextCursor: null }));
    await listJobs({ cursor: "abc", status: "FAILED", limit: 5 });
    expect(lastCall().url).toBe("http://localhost:8080/api/v1/jobs?limit=5&cursor=abc&status=FAILED");
    await listJobs();
    expect(lastCall().url).toBe("http://localhost:8080/api/v1/jobs?limit=20");
  });

  it("getJob escapa el identificador", async () => {
    fetchMock.mockResolvedValue(jsonResponse(makeJob()));
    await getJob("a/b");
    expect(lastCall().url).toBe("http://localhost:8080/api/v1/jobs/a%2Fb");
  });

  it("retryJob hace POST a /retry", async () => {
    fetchMock.mockResolvedValue(jsonResponse(makeJob(), 202));
    await retryJob("abc");
    expect(lastCall().url).toBe("http://localhost:8080/api/v1/jobs/abc/retry");
    expect(lastCall().init.method).toBe("POST");
  });

  it("downloadHref antepone la URL de la API", () => {
    expect(
      downloadHref({ name: "r.csv", contentType: "text/csv", size: 1, downloadUrl: "/api/v1/jobs/abc/result" }),
    ).toBe("http://localhost:8080/api/v1/jobs/abc/result");
  });
});

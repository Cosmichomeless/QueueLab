import { afterEach, describe, expect, it, vi } from "vitest";
import { apiUrl, DEFAULT_API_URL, runtimeConfigScript, serverApiUrl } from "./config";

afterEach(() => {
  vi.unstubAllEnvs();
  delete window.__QUEUELAB_API_URL__;
});

describe("serverApiUrl", () => {
  it("usa localhost:8080 si no hay configuración", () => {
    vi.stubEnv("QUEUELAB_API_URL", "");
    vi.stubEnv("NEXT_PUBLIC_API_URL", "");
    expect(serverApiUrl()).toBe(DEFAULT_API_URL);
  });

  it("prefiere QUEUELAB_API_URL a NEXT_PUBLIC_API_URL", () => {
    vi.stubEnv("QUEUELAB_API_URL", "https://api.example.com");
    vi.stubEnv("NEXT_PUBLIC_API_URL", "http://otra:1");
    expect(serverApiUrl()).toBe("https://api.example.com");
  });

  it("cae a NEXT_PUBLIC_API_URL (desarrollo local) y quita las barras finales", () => {
    vi.stubEnv("QUEUELAB_API_URL", "");
    vi.stubEnv("NEXT_PUBLIC_API_URL", "http://otra:1//");
    expect(serverApiUrl()).toBe("http://otra:1");
  });
});

describe("apiUrl", () => {
  it("en el navegador manda el valor inyectado por el servidor", () => {
    vi.stubEnv("QUEUELAB_API_URL", "http://servidor:1");
    window.__QUEUELAB_API_URL__ = "https://api.example.com/";
    expect(apiUrl()).toBe("https://api.example.com");
  });

  it("sin valor inyectado usa el entorno", () => {
    vi.stubEnv("QUEUELAB_API_URL", "http://servidor:1");
    expect(apiUrl()).toBe("http://servidor:1");
  });
});

describe("runtimeConfigScript", () => {
  it("publica la URL en window", () => {
    expect(runtimeConfigScript("https://api.example.com")).toBe(
      'window.__QUEUELAB_API_URL__="https://api.example.com";',
    );
  });

  it("no deja que el valor cierre la etiqueta script", () => {
    const script = runtimeConfigScript('http://x/</script><script>alert(1)');
    expect(script).not.toContain("<");
    // Ejecutado, devuelve exactamente el valor original.
    new Function("window", script)(window);
    expect(window.__QUEUELAB_API_URL__).toBe("http://x/</script><script>alert(1)");
  });
});

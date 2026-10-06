import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Sin `globals`, Testing Library no se entera de que acaba cada test: desmontamos a mano.
afterEach(cleanup);

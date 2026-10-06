import type { Metadata } from "next";
import { CsvUploadForm } from "@/components/CsvUploadForm";

export const metadata: Metadata = { title: "Nuevo CSV · QueueLab" };

export default function NewJobPage() {
  return <CsvUploadForm />;
}

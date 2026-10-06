import type { Metadata } from "next";
import { JobList } from "@/components/JobList";

export const metadata: Metadata = { title: "Trabajos · QueueLab" };

export default function JobsPage() {
  return <JobList />;
}

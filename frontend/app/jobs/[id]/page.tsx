import type { Metadata } from "next";
import { JobDetail } from "@/components/JobDetail";

export const metadata: Metadata = { title: "Trabajo · QueueLab" };

export default async function JobPage({ params }: PageProps<"/jobs/[id]">) {
  const { id } = await params;
  return <JobDetail id={id} />;
}

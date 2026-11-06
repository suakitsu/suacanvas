'use client';

import { WorkspaceShell } from '@/components/workspace/WorkspaceShell';
import { PicPilotEmbed } from '@/components/PicPilotEmbed';

export default function Home() {
  return (
    <PicPilotEmbed>
      <WorkspaceShell />
    </PicPilotEmbed>
  );
}

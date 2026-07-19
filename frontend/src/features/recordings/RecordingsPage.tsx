import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { fetchPlaybackUrl, useRecordings } from './recordingApi';

export function RecordingsPage() {
  const { meetingId } = useParams<{ meetingId: string }>();
  const { data: recordings, isLoading } = useRecordings(meetingId!);
  const [playing, setPlaying] = useState<string | null>(null);

  async function play(recordingId: string) {
    // the presigned URL lives one hour, so only fetch it when the user hits play
    setPlaying(await fetchPlaybackUrl(recordingId));
  }

  return (
    <div className="min-h-screen bg-gray-50 p-6 max-w-3xl mx-auto space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold">Meeting recordings</h1>
        <Link to="/" className="text-blue-600 text-sm">← Back to dashboard</Link>
      </div>
      {playing && (
        <video controls autoPlay src={playing} className="w-full rounded-xl shadow bg-black" />
      )}
      <section className="bg-white rounded-xl shadow divide-y">
        {isLoading && <p className="px-4 py-3 text-gray-500">Loading...</p>}
        {recordings?.length === 0 && (
          <p className="px-4 py-3 text-gray-500">No recordings yet</p>
        )}
        {recordings?.map((r) => (
          <div key={r.id} className="px-4 py-3 flex items-center justify-between text-sm">
            <div>
              <p>{new Date(r.startedAt).toLocaleString('en-US')}</p>
              <p className="text-gray-500">
                {r.status}{r.durationSeconds ? ` · ${Math.round(r.durationSeconds / 60)} min` : ''}
              </p>
            </div>
            {r.status === 'COMPLETED' && (
              <button onClick={() => void play(r.id)}
                      className="text-blue-600 font-medium hover:underline">
                ▶ Play
              </button>
            )}
          </div>
        ))}
      </section>
    </div>
  );
}

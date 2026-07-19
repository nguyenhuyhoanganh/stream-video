export type User = { id: string; email: string; fullName: string };

export type AuthResponse = { accessToken: string; user: User };

export type Meeting = {
  id: string;
  code: string;
  title: string;
  description: string | null;
  hostId: string;
  scheduledStartAt: string;
  scheduledEndAt: string | null;
  status: 'SCHEDULED' | 'LIVE' | 'ENDED' | 'CANCELLED';
  roomType: 'MEETING' | 'WEBINAR';
};

export type JoinResponse = { livekitUrl: string; livekitToken: string; role: 'HOST' | 'SPEAKER' };

export type ApiError = { status: number; detail: string; code: string; fields?: Record<string, string> };

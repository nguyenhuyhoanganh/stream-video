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

export type MeetingRole = 'HOST' | 'SPEAKER' | 'ATTENDEE';

export type JoinResponse = {
  meetingId: string;
  livekitUrl: string;
  livekitToken: string;
  role: MeetingRole;
  chatToken: string | null;
};

export type ChatMessageDto = {
  id: string;
  meetingId: string;
  senderIdentity: string;
  senderDisplayName: string;
  content: string;
  type: 'TEXT' | 'SYSTEM' | 'RAISE_HAND';
  createdAt: string;
};

export type ChatEvent =
  | { kind: 'MESSAGE'; message: ChatMessageDto; messageId: null }
  | { kind: 'MESSAGE_DELETED'; message: null; messageId: string };

export type ApiError = { status: number; detail: string; code: string; fields?: Record<string, string> };

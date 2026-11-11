CREATE TABLE meeting_rooms (
 id bigserial PRIMARY KEY, name varchar(100) NOT NULL, location varchar(200),
 capacity integer CHECK(capacity>0), enabled boolean NOT NULL DEFAULT true
);
CREATE UNIQUE INDEX meeting_rooms_enabled_name ON meeting_rooms(lower(name)) WHERE enabled;
CREATE TABLE meetings (
 id bigserial PRIMARY KEY, organizer_id bigint NOT NULL REFERENCES users(id),
 title varchar(200) NOT NULL, start_at timestamp NOT NULL, end_at timestamp NOT NULL,
 room_id bigint REFERENCES meeting_rooms(id), description varchar(2000),
 cancelled_at timestamp, created_at timestamp NOT NULL DEFAULT current_timestamp,
 updated_at timestamp NOT NULL DEFAULT current_timestamp, CHECK(end_at>start_at)
);
CREATE INDEX meetings_time ON meetings(start_at,end_at);
CREATE INDEX meetings_room ON meetings(room_id,start_at,end_at) WHERE cancelled_at IS NULL;
CREATE TABLE meeting_attendees (
 meeting_id bigint NOT NULL REFERENCES meetings(id), user_id bigint NOT NULL REFERENCES users(id),
 response varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(response IN ('PENDING','ACCEPTED','DECLINED')),
 reason varchar(300), responded_at timestamp, PRIMARY KEY(meeting_id,user_id)
);
CREATE INDEX meeting_attendees_user ON meeting_attendees(user_id,meeting_id);
ALTER TABLE notifications ADD COLUMN meeting_id bigint REFERENCES meetings(id);

-- Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
-- and the EPL 1.0 (https://h2database.com/html/license.html).
-- Initial Developer: H2 Group
--

-- Test for ALTER TABLE RENAME and ALTER VIEW RENAME

CREATE TABLE TABLE1A(ID INT);
> ok

INSERT INTO TABLE1A VALUES (1);
> update count: 1

-- ALTER TABLE RENAME

ALTER TABLE TABLE1A RENAME TO TABLE1B;
> ok

SELECT * FROM TABLE1B;
>> 1

ALTER TABLE IF EXISTS TABLE1B RENAME TO TABLE1C;
> ok

SELECT * FROM TABLE1C;
>> 1

ALTER TABLE BAD RENAME TO SMTH;
> exception TABLE_OR_VIEW_NOT_FOUND_1

ALTER TABLE IF EXISTS BAD RENAME TO SMTH;
> ok

-- ALTER VIEW RENAME

CREATE VIEW VIEW1A AS SELECT * FROM TABLE1C;
> ok

ALTER VIEW VIEW1A RENAME TO VIEW1B;
> ok

SELECT * FROM VIEW1B;
>> 1

ALTER TABLE IF EXISTS VIEW1B RENAME TO VIEW1C;
> ok

SELECT * FROM VIEW1C;
>> 1

ALTER VIEW BAD RENAME TO SMTH;
> exception VIEW_NOT_FOUND_1

ALTER VIEW IF EXISTS BAD RENAME TO SMTH;
> ok

SELECT * FROM VIEW1C;
>> 1

DROP TABLE TABLE1C CASCADE;
> ok

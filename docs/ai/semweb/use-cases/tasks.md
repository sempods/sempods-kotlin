# Task Use Cases Playbook (Ready To Use, IST)

## Scope

This document provides a use-case-driven guide for apps that work with tasks via:

- `POST /{pod}/_system/ai/semweb/text2model`
- `POST /{pod}/_system/ai/semweb/model2model`

Current focus (IST):

- create task models from user text
- update existing task models from follow-up user text

Out of scope for now:

- finding/searching tasks (no AI prompt patterns required yet)

This document is intentionally use-case driven (tasks) instead of endpoint-type driven.

## Contract Source (Code)

For exact request/response and validation details, use:

- `sempods-server/src/main/kotlin/org/sempods/api/pod/system/ai/semweb/PodAiSemWebEndpoint.kt`
- `sempods-server/src/main/kotlin/org/sempods/ai/sem/AiSemFacade.kt`
- `sempods-server/src/main/kotlin/org/sempods/ai/sem/AiSemText2ModelRequest.kt`
- `sempods-server/src/main/kotlin/org/sempods/ai/sem/AiSemModel2ModelRequest.kt`

## Task Model (IST for This Playbook)

This playbook uses `https://schema.org/PlanAction` for tasks.

Type:

- `https://schema.org/PlanAction`: The act of planning the execution of an event/task/action/reservation/plan to a future date.

Properties used by this playbook:

- `https://schema.org/name`: task title
- `https://schema.org/description`: task details / free-form notes
- `https://schema.org/scheduledTime`: planning anchor for when the task should become visible/actionable
- `https://schema.org/actionStatus`: current disposition of the action
- `https://schema.org/startTime`: actual start time of the underlying event/action (if distinct from reminder/planning time)
- `https://schema.org/endTime`: actual end time (if known)

Status values currently documented in this playbook:

- `https://schema.org/CompletedActionStatus`

Important app convention used here (not server-enforced):

- `scheduledTime` is the primary task planning/visibility anchor.
- `startTime` / `endTime` describe the actual event window if the task is a reminder/preparation for another event.

## Time Normalization (Recommended App Convention for Examples)

The endpoints inject current server time context automatically (UTC date/time/weekday). The AI can resolve relative phrases such as "Tuesday" or "next month".

Because this playbook stores time fields as `xsd:dateTime`, examples use this deterministic fallback convention for incomplete user timing:

- date without time -> use `09:00:00Z`
- month only -> use the first day of that month at `09:00:00Z`
- update with date-only -> preserve the existing time if the target field already exists
- update with time-only -> preserve the existing date if the target field already exists

If your app does not want inferred precision, remove these fallback rules from `guidance.instructions` and allow omitted date/time fields.

## Reusable SHACL Profile (Tasks / `schema:PlanAction`)

Use the same SHACL profile for both `text2model` and `model2model`.

```turtle
@prefix sh: <http://www.w3.org/ns/shacl#> .
@prefix schema: <https://schema.org/> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

<urn:shape:TaskPlanAction> a sh:NodeShape ;
  sh:targetClass schema:PlanAction ;
  sh:description "Extract or update one task as schema:PlanAction."@en ;
  sh:property [
    sh:path schema:name ;
    sh:datatype xsd:string ;
    sh:description "Short task title."@en ;
  ] ;
  sh:property [
    sh:path schema:description ;
    sh:datatype xsd:string ;
    sh:description "Task details and appended notes as plain text."@en ;
  ] ;
  sh:property [
    sh:path schema:scheduledTime ;
    sh:datatype xsd:dateTime ;
    sh:description "Planning/visibility anchor time for the task."@en ;
  ] ;
  sh:property [
    sh:path schema:actionStatus ;
    sh:nodeKind sh:IRI ;
    sh:description "Action status IRI. Use schema:CompletedActionStatus when the task is completed."@en ;
  ] ;
  sh:property [
    sh:path schema:startTime ;
    sh:datatype xsd:dateTime ;
    sh:description "Actual event/action start time if different from scheduledTime."@en ;
  ] ;
  sh:property [
    sh:path schema:endTime ;
    sh:datatype xsd:dateTime ;
    sh:description "Actual event/action end time if known."@en ;
  ] .
```

## Ready-To-Use Guidance Prompts

These are client-side guidance strings (`guidance.instructions`). Start with SHACL-only first. Add guidance only if your app needs more deterministic behavior.

### `text2model` Guidance (Task Creation)

```text
Extract exactly one task as https://schema.org/PlanAction.
Output only fields allowed by the SHACL target.
Use https://schema.org/name for a short task title.
Use https://schema.org/description for extra details, people, phone numbers, and original wording that should not be lost.
Use https://schema.org/scheduledTime as the planning/visibility anchor when the user specifies when to do the task or when the task should become visible.
Use https://schema.org/startTime / endTime for the actual event window when the text describes an event that the task refers to.
If the text contains both a reminder/planning time and an event time, emit both: scheduledTime for the reminder/planning anchor and startTime/endTime for the event.
Do not replace an event time with scheduledTime and do not replace scheduledTime with an event time.
Use https://schema.org/actionStatus only when the user clearly indicates a status (for now mainly CompletedActionStatus).
Do not invent scheduledTime/startTime/endTime when the user gave no timing expression.
For German weekdays resolve strictly as: Montag=Monday, Dienstag=Tuesday, Mittwoch=Wednesday, Donnerstag=Thursday, Freitag=Friday, Samstag=Saturday, Sonntag=Sunday.
For weekday phrases such as 'am Dienstag', choose the next upcoming occurrence of that weekday relative to the server-provided current date context.
If the current day already is that weekday and the phrasing is not 'nächsten', using today is allowed.
Do not emit null values.
Resolve relative dates/times from the server-provided current date context.
Fallback convention for incomplete timing:
- date-only -> 09:00:00Z
- month-only or month-relative-only (for example 'im Oktober', 'nächsten Monat') -> first day of the target month at 09:00:00Z
- do not use midnight for the fallback convention unless the user explicitly requested 00:00
```

### `model2model` Guidance (Task Update from User Text)

```text
Update the existing task model using the user instruction.
Preserve @id and @type.
Keep unrelated fields unchanged.
Prefer editing the smallest necessary field(s).
If the user adds more detail (person, phone number, note), append it to https://schema.org/description as plain text.
If the user changes day/date, update https://schema.org/scheduledTime and preserve existing time if present.
If the user changes only time, update https://schema.org/scheduledTime and preserve existing date if present.
If the user provides the actual event date/time (for example 'findet am ... statt'), update https://schema.org/startTime (and endTime only if stated).
When updating startTime/endTime for the event, do not also change scheduledTime unless the instruction explicitly changes reminder/planning timing.
For German weekdays resolve strictly as: Montag=Monday, Dienstag=Tuesday, Mittwoch=Wednesday, Donnerstag=Thursday, Freitag=Friday, Samstag=Saturday, Sonntag=Sunday.
For weekday phrases such as 'am Freitag', choose the next upcoming occurrence relative to the server-provided current date context.
Do not emit null values and do not drop existing values unless the instruction clearly requests removal.
Resolve relative dates/times from the server-provided current date context.
If the instruction is a relative duration (for example 'in 3 Tagen') and the target field exists, shift that field relative to its current value.
If the instruction expresses only a month/month-bucket (for example 'im Oktober', 'nächsten Monat') and no day/time, normalize scheduledTime to the first day of the target month at 09:00:00Z.
If scheduledTime does not exist yet and the user gives a month/month-relative phrase, create scheduledTime using the same first-day-of-month 09:00:00Z fallback.
Do not use midnight for the fallback convention unless the user explicitly requested 00:00.
```

### Optional `guidance.examples` (Recommended for Small Models)

If you use a smaller local model (for example `qwen2.5:7b`), add `guidance.examples` in addition to `guidance.instructions`.

The endpoint accepts any JSON structure for `guidance.examples`. A simple, explicit array works well.

### `text2model` `guidance.examples` (Recommended)

```json
[
  {
    "case": "month-only planning time",
    "inputText": "Im März mit Thomas ins Kino gehen",
    "expected": {
      "@graph": [
        {
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/scheduledTime": "2026-03-01T09:00:00Z"
        }
      ]
    },
    "notes": "Month-only -> first day of month at 09:00:00Z."
  },
  {
    "case": "weekday resolution (German)",
    "assumeCurrentDateUtc": "2026-02-22",
    "inputText": "Am Dienstag die Versicherung anrufen",
    "expected": {
      "@graph": [
        {
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/scheduledTime": "2026-02-24T09:00:00Z"
        }
      ]
    },
    "notes": "Dienstag = Tuesday."
  },
  {
    "case": "reminder task plus actual event date",
    "inputText": "Erinner mich für Klassentreffen am 7.11.2026 nochmal im Oktober",
    "expected": {
      "@graph": [
        {
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/scheduledTime": "2026-10-01T09:00:00Z",
          "https://schema.org/startTime": "2026-11-07T09:00:00Z"
        }
      ]
    },
    "notes": "Reminder month goes to scheduledTime, event date goes to startTime."
  },
  {
    "case": "no timing mentioned",
    "inputText": "Zimmer von Lennox neu gestalten",
    "expected": {
      "@graph": [
        {
          "@type": "https://schema.org/PlanAction"
        }
      ]
    },
    "notes": "Do not invent scheduledTime."
  }
]
```

### `model2model` `guidance.examples` (Recommended)

```json
[
  {
    "case": "append description note",
    "instructionText": "Erik kommt mit",
    "currentModel": {
      "@graph": [
        {
          "@id": "urn:task:cinema:1",
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/name": "Mit Thomas ins Kino gehen",
          "https://schema.org/description": "Film: Good Luck, Have Fun, Don’t Die",
          "https://schema.org/scheduledTime": "2026-03-01T09:00:00Z"
        }
      ]
    },
    "expectedModel": {
      "@graph": [
        {
          "@id": "urn:task:cinema:1",
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/name": "Mit Thomas ins Kino gehen",
          "https://schema.org/description": "Film: Good Luck, Have Fun, Don’t Die. Erik kommt mit",
          "https://schema.org/scheduledTime": "2026-03-01T09:00:00Z"
        }
      ]
    }
  },
  {
    "case": "weekday shift for task schedule",
    "assumeCurrentDateUtc": "2026-02-22",
    "instructionText": "Das mache ich erst am Freitag",
    "currentModel": {
      "@graph": [
        {
          "@id": "urn:task:insurance:1",
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/scheduledTime": "2026-02-24T09:00:00Z"
        }
      ]
    },
    "expectedModel": {
      "@graph": [
        {
          "@id": "urn:task:insurance:1",
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/scheduledTime": "2026-02-27T09:00:00Z"
        }
      ]
    }
  },
  {
    "case": "event time update should not overwrite reminder schedule",
    "instructionText": "Das findet am 12.7.2026 17 Uhr statt",
    "currentModel": {
      "@graph": [
        {
          "@id": "urn:task:reunion:1",
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/scheduledTime": "2026-10-01T09:00:00Z",
          "https://schema.org/startTime": "2026-11-07T09:00:00Z"
        }
      ]
    },
    "expectedModel": {
      "@graph": [
        {
          "@id": "urn:task:reunion:1",
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/scheduledTime": "2026-10-01T09:00:00Z",
          "https://schema.org/startTime": "2026-07-12T17:00:00Z"
        }
      ]
    },
    "notes": "Update startTime only."
  },
  {
    "case": "month-relative scheduling fallback",
    "assumeCurrentDateUtc": "2026-02-22",
    "instructionText": "nächsten Monat",
    "currentModel": {
      "@graph": [
        {
          "@id": "urn:task:lennox-room:1",
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/name": "Zimmer von Lennox neu gestalten"
        }
      ]
    },
    "expectedModel": {
      "@graph": [
        {
          "@id": "urn:task:lennox-room:1",
          "@type": "https://schema.org/PlanAction",
          "https://schema.org/scheduledTime": "2026-03-01T09:00:00Z"
        }
      ]
    },
    "notes": "Month-relative phrase without day/time -> first day of target month at 09:00:00Z."
  }
]
```

## Flow A: Create a New Task from Free Text (`text2model`)

1. App receives user task text.
2. App calls `POST /{pod}/_system/ai/semweb/text2model` with:
   - task SHACL profile (`schema:PlanAction`)
   - optional `guidance.instructions` (task-specific rules above)
   - optional `guidance.examples` (recommended for smaller models)
3. App checks response:
   - `status=ok` -> persist task graph
   - `status=empty` -> ask user for clarification or keep draft text
4. App may add app-specific metadata after AI extraction (`@id`, storage context, app tags, audit info).

### Request Template (`text2model`)

```json
{
  "content": {
    "text": "<user text>",
    "language": "de"
  },
  "target": {
    "shacl": {
      "syntax": "text/turtle",
      "data": "<Task SHACL turtle from this document>"
    }
  },
  "guidance": {
    "instructions": "<text2model task guidance from this document>",
    "examples": []
  },
  "options": {
    "strict": true,
    "allowEmpty": true
  }
}
```

Replace `[]` with the optional JSON example array from the `text2model` examples section in this document (or omit the field).

## Create Scenarios (Task Extraction)

Assumption for relative-date examples below:

- server current date context = `2026-02-22` (Sunday, UTC)

The endpoint injects current date/time automatically. Real output depends on runtime date.

### 1. Cinema Plan in March

User text:

```text
Im März mit Thomas ins Kino gehen zu 'Good Luck, Have Fun, Don’t Die'
```

Example extracted graph (using the fallback convention):

```json
{
  "@graph": [
    {
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Mit Thomas ins Kino gehen",
      "https://schema.org/description": "Film: Good Luck, Have Fun, Don’t Die",
      "https://schema.org/scheduledTime": "2026-03-01T09:00:00Z"
    }
  ]
}
```

### 2. Call Insurance on Tuesday

User text:

```text
Am Dienstag die Versicherung wegen Haftpflicht anrufen
```

Example extracted graph:

```json
{
  "@graph": [
    {
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Versicherung anrufen",
      "https://schema.org/description": "Wegen Haftpflicht",
      "https://schema.org/scheduledTime": "2026-02-24T09:00:00Z"
    }
  ]
}
```

### 3. Reminder for Class Reunion (event date + reminder month)

User text:

```text
Erinner das mich für Klassentreffen am 7.11.2026 nochmal im Oktober
```

Example extracted graph:

```json
{
  "@graph": [
    {
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Klassentreffen Erinnerung",
      "https://schema.org/description": "Nochmal erinnern",
      "https://schema.org/scheduledTime": "2026-10-01T09:00:00Z",
      "https://schema.org/startTime": "2026-11-07T09:00:00Z"
    }
  ]
}
```

Interpretation used here:

- `scheduledTime` = reminder task anchor ("in October")
- `startTime` = actual event date ("7.11.2026")

### 4. Redesign Lennox's Room

User text:

```text
Zimmer von Lennox neu gestalten
```

Example extracted graph:

```json
{
  "@graph": [
    {
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Zimmer von Lennox neu gestalten"
    }
  ]
}
```

No timing is stated, so no `scheduledTime` is emitted.

## Flow B: Update an Existing Task from User Text (`model2model`)

Use this when the app already has a task model and the user sends a short follow-up text like "Friday instead" or "Number: ...".

1. App loads the current task JSON-LD model.
2. App calls `POST /{pod}/_system/ai/semweb/model2model` with:
   - `currentModel`
   - the same task SHACL profile
   - optional update guidance (`guidance.instructions`)
   - optional `guidance.examples` (recommended for smaller models)
3. App compares old/new models (recommended) and persists the result.
4. App may run business checks after AI update (for example: reminder should not be after the actual event date).

### Request Template (`model2model`)

```json
{
  "instruction": {
    "text": "<user follow-up text>",
    "language": "de"
  },
  "currentModel": {
    "@graph": [
      {
        "@id": "urn:task:example",
        "@type": "https://schema.org/PlanAction"
      }
    ]
  },
  "target": {
    "shacl": {
      "syntax": "text/turtle",
      "data": "<Task SHACL turtle from this document>"
    }
  },
  "guidance": {
    "instructions": "<model2model task guidance from this document>",
    "examples": []
  },
  "options": {
    "strict": true,
    "allowNoChange": true
  }
}
```

Replace `[]` with the optional JSON example array from the `model2model` examples section in this document (or omit the field).

## Update Scenarios (Task Changes from Follow-Up Text)

Assumption for relative-date examples below:

- server current date context = `2026-02-22` (Sunday, UTC)

### Base Current Models Used in Examples

Task 1 (cinema):

```json
{
  "@graph": [
    {
      "@id": "urn:task:cinema:1",
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Mit Thomas ins Kino gehen",
      "https://schema.org/description": "Film: Good Luck, Have Fun, Don’t Die",
      "https://schema.org/scheduledTime": "2026-03-01T09:00:00Z"
    }
  ]
}
```

Task 2 (insurance call):

```json
{
  "@graph": [
    {
      "@id": "urn:task:insurance:1",
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Versicherung anrufen",
      "https://schema.org/description": "Wegen Haftpflicht",
      "https://schema.org/scheduledTime": "2026-02-24T09:00:00Z"
    }
  ]
}
```

Task 3 (class reunion reminder):

```json
{
  "@graph": [
    {
      "@id": "urn:task:reunion:1",
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Klassentreffen Erinnerung",
      "https://schema.org/description": "Nochmal erinnern",
      "https://schema.org/scheduledTime": "2026-10-01T09:00:00Z",
      "https://schema.org/startTime": "2026-11-07T09:00:00Z"
    }
  ]
}
```

Task 4 (Lennox room):

```json
{
  "@graph": [
    {
      "@id": "urn:task:lennox-room:1",
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Zimmer von Lennox neu gestalten"
    }
  ]
}
```

### 1) Update Task 1: Add Person to Description

User follow-up:

```text
Erik kommt mit
```

Expected change:

- append text to `https://schema.org/description`

Example result:

```json
{
  "@graph": [
    {
      "@id": "urn:task:cinema:1",
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Mit Thomas ins Kino gehen",
      "https://schema.org/description": "Film: Good Luck, Have Fun, Don’t Die. Erik kommt mit.",
      "https://schema.org/scheduledTime": "2026-03-01T09:00:00Z"
    }
  ]
}
```

### 2) Update Task 2: Shift to Friday

User follow-up:

```text
Das mache ich erst am Freitag
```

Expected change:

- move `https://schema.org/scheduledTime` to Friday
- preserve time (`09:00:00Z`)

Example result:

```json
{
  "@graph": [
    {
      "@id": "urn:task:insurance:1",
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Versicherung anrufen",
      "https://schema.org/description": "Wegen Haftpflicht",
      "https://schema.org/scheduledTime": "2026-02-27T09:00:00Z"
    }
  ]
}
```

### 2b) Update Task 2: Earliest Call Time 11:00

User follow-up:

```text
Die Versicherung kann ich erst ab 11 Uhr anrufen
```

Expected change:

- update only time part of `https://schema.org/scheduledTime`
- preserve date (`2026-02-24`)

Example result:

```json
{
  "@graph": [
    {
      "@id": "urn:task:insurance:1",
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Versicherung anrufen",
      "https://schema.org/description": "Wegen Haftpflicht",
      "https://schema.org/scheduledTime": "2026-02-24T11:00:00Z"
    }
  ]
}
```

### 2c) Update Task 2: Add Phone Number

User follow-up:

```text
Nummer: 01376/343234
```

Expected change:

- append text to `https://schema.org/description`

Example result:

```json
{
  "@graph": [
    {
      "@id": "urn:task:insurance:1",
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Versicherung anrufen",
      "https://schema.org/description": "Wegen Haftpflicht. Nummer: 01376/343234",
      "https://schema.org/scheduledTime": "2026-02-24T09:00:00Z"
    }
  ]
}
```

### 3) Update Task 3: Change Actual Event Date/Time

User follow-up:

```text
Das findet am 12.7.2026 17 Uhr statt
```

Expected change:

- update `https://schema.org/startTime`
- keep reminder task fields unchanged unless explicitly requested

Example result:

```json
{
  "@graph": [
    {
      "@id": "urn:task:reunion:1",
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Klassentreffen Erinnerung",
      "https://schema.org/description": "Nochmal erinnern",
      "https://schema.org/scheduledTime": "2026-10-01T09:00:00Z",
      "https://schema.org/startTime": "2026-07-12T17:00:00Z"
    }
  ]
}
```

App note:

- This may create an inconsistent reminder (October) for an event in July.
- The endpoint updates the model, but your app should run a domain rule check and ask for confirmation or adjust `scheduledTime`.

### 4) Update Task 4: Schedule for Next Month

User follow-up:

```text
nächsten Monat
```

Expected change:

- set `https://schema.org/scheduledTime` relative to current date context (because the field is not present yet)

Example result (with the fallback convention):

```json
{
  "@graph": [
    {
      "@id": "urn:task:lennox-room:1",
      "@type": "https://schema.org/PlanAction",
      "https://schema.org/name": "Zimmer von Lennox neu gestalten",
      "https://schema.org/scheduledTime": "2026-03-01T09:00:00Z"
    }
  ]
}
```

## Local Testing (Optional)

You can test the examples locally with `curl` or Postman against:

- `POST /{pod}/_system/ai/semweb/text2model`
- `POST /{pod}/_system/ai/semweb/model2model`

Headers:

- `Authorization: Bearer <pod-app-token>`
- `Content-Type: application/json`
- `Accept: application/ld+json`

Note:

- Do not hardcode real tokens in documentation or committed files.
- The server already injects current UTC date/time context; do not send a client-side `currentTimestamp`.

## Task Finding (Later)

Task search/find scenarios are intentionally not covered here yet because they currently do not require AI prompt patterns.

When this is documented later, it should focus on:

- app query patterns
- filtering by `scheduledTime`, `actionStatus`, and text fields
- how search UX maps to semweb queries (without mixing it into AI prompt docs)

## Related Docs

- `docs/ai/semweb/text2model.md` (endpoint behavior IST)
- `docs/ai-layer.md` (AI layer IST)
- AI/SemWeb roadmap (SOLL, currently internal)

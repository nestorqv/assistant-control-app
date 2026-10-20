# Mermaid Syntax Cheat Sheet

Compact reference for architecture-diagrams.

## ER Diagrams
- Start with `erDiagram`.
- Entity syntax:
```mermaid
ENTITY {
  type name "comment"
}
```
- Common attribute types: `string`, `int`, `long`, `date`, `datetime`, `boolean`
- Key markers: `PK`, `FK`
- Relationship cardinality:
  - `||--||` one-to-one
  - `||--o{` one-to-many
  - `}o--o{` many-to-many
  - `o|` zero or one

Example:
```mermaid
erDiagram
  USERS {
    uuid id PK "Primary key"
    string email UK "Unique"
    string name "Display name"
    timestamp created_at "ISO datetime"
  }
  ORDERS {
    uuid id PK "Primary key"
    uuid user_id FK "References users"
    int total "Amount in cents"
    string status "pending | shipped | delivered"
  }
  USERS ||--o{ ORDERS : places
```

## Flowcharts
- Layout directions: `flowchart TD` (top-down), `flowchart LR` (left-right)
- Node shapes:
  - `[text]` rectangle
  - `(text)` rounded
  - `{text}` decision/diamond
  - `[(text)]` database cylinder
- Arrows: `-->` solid, `-.->` dotted, `==>` thick
- Arrow label syntax: `-->|label|`
- Subgraph block:
```mermaid
subgraph Layer Name
  A --> B
end
```

Example (layered architecture):
```mermaid
flowchart TD
  subgraph Frontend
    UI[Web App] --> Client[API Client]
  end
  subgraph API Layer
    API[REST Endpoints]
  end
  subgraph Services
    Svc[Business Logic]
  end
  subgraph Persistence
    DB[(Database)]
    Cache[(Cache)]
  end
  Client --> API
  API --> Svc
  Svc --> DB
  Svc -.->|optional| Cache
```

## Sequence Diagrams
- Start with `sequenceDiagram`
- Participant alias syntax:
```mermaid
participant C as Component
```
- Arrows: `->>` sync call, `-->>` response, `--)` async
- Call stack focus: `activate`, `deactivate`
- Conditions: `alt`, `else`, `end`
- Notes: `Note over A,B: text`
- Highlight section: `rect rgb(...)` ... `end`

Example (generic API request flow):
```mermaid
sequenceDiagram
  participant U as Client
  participant A as API Server
  participant S as Service Layer
  participant DB as Database

  U->>A: POST /resource (payload)
  activate A
  A->>S: processRequest(data)
  activate S
  S->>DB: INSERT INTO resources
  DB-->>S: OK (id)
  deactivate S
  S-->>A: ResourceDTO
  A-->>U: 201 Created
  deactivate A

  alt Validation Error
    A-->>U: 400 Bad Request
  end
```

## Styling
- Class definition:
```mermaid
classDef className fill:#color,stroke:#color,color:#fontcolor
```
- Assign class to node:
```mermaid
class nodeId className
```
- Suggested layer palettes:
  - Frontend/UI: blue (`#dbeafe`, `#93c5fd`, `#1e3a8a`)
  - API: green (`#dcfce7`, `#86efac`, `#14532d`)
  - Services: purple (`#ede9fe`, `#a78bfa`, `#3b0764`)
  - External APIs: amber (`#fef3c7`, `#fbbf24`, `#78350f`)
  - Persistence/DB: gray (`#e5e7eb`, `#9ca3af`, `#111827`)

Example styling:
```mermaid
flowchart LR
  UI[Frontend] --> API[API Layer] --> Svc[Services] --> DB[(Database)]
  classDef frontend fill:#dbeafe,stroke:#93c5fd,color:#1e3a8a
  classDef api fill:#dcfce7,stroke:#86efac,color:#14532d
  classDef services fill:#ede9fe,stroke:#a78bfa,color:#3b0764
  classDef persistence fill:#e5e7eb,stroke:#9ca3af,color:#111827
  class UI frontend
  class API api
  class Svc services
  class DB persistence
```

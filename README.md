# **YourOwnVariables (YOV)**
A flexible and powerful variable management system for Minecraft servers.

---

**Requires:** [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)  
Works on all Spigot/Paper versions **1.16.5 – 1.21.11**

---

## ✨ **Features**

### ✔ Global Variables
- `%yov_<variable>%`

### ✔ Player Variables
- Stored as: `<player>_<variable>`
- Accessed via: `%yov_player_key:<variable>%`

### ✔ Storage Migration System
```bash
/yov migrate <from> <to>
```

### ✔ Commands
- `/yov help`
- `/yov reload`
- `/yov set <variable> <value> [player] [-s]`
- `/yov add <variable> <amount> [player] [-s]`
- `/yov rem <variable> <amount> [player] [-s]`
- `/yov delete <variable> [player] [-s]`
- `/yov check <variable> [player]`
- `/yov migrate <from> <to>`
- `/yov userclear <player>`

---

## 🧩 **Commands & Usage**

### Creating variables
```bash
/yov set test 1
/yov set test 1.0
/yov set test hello world
/yov set level 5 playerName
```

### Modifying variables
```bash
/yov add test 5
/yov rem test 1
/yov delete test
```

### Silent mode
```bash
/yov add test 1 player -s
```

---

## 🔍 **Checking Variables**
```bash
/yov check test
/yov check playerName_coins
```

---

## 🔗 **PlaceholderAPI Usage**

**Global variable**
```bash
%yov_test%
```

**Player variable**
```bash
%yov_player_key:coins%
```

**Rounded values**
```bash
%rounded:coins%
%rounded_1:coins%
%rounded_2:coins%
%rounded_player_key:coins%
%rounded_player_key_2:coins%
```

---

## 📦 **Default variables**
```yml
variables:
  claim_points: "200"
```

---

## ⚙️ **Integration Example (ConditionalEvents)**
```yml
conditions:
  check-level: '%yov_player_key:level% >= 20'
```

**Dynamic variable creation**
```bash
/yov set example 1 %player_name% -s
```

---

## 🔑 **Permissions**

| Permission  | Description |
|------------|------------|
| `yov.admin` | Full access |

---

## 💾 **Storage Types**

Supports:
- YAML
- SQLite
- MariaDB

Switch storage:
```bash
/yov migrate <old> <new>
```

---

## 📁 **YAML Structure Example**
```yml
global:
  season: "1"

players:
  haizenfell:
    coins: "500"
    kills: "10"
```
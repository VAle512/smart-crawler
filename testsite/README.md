This is a mock site for unit testing. Run the command `python -m SimpleHTTPServer 8081` before the JUnit test suite.

| Page Class | Number of pages |
| --- | --- |
| Homepage	| 1 |
| Directory1 | 2 |
| Directory2 | 1 |
| Detail |	4 |
| About	| 1 |
| Table	| 1 |

Navigazione:

Homepage -> Directory1, Directory2, About, Table
Directory1 -> Directory1, Detail
Directory2 -> Detail
Detail -> Directory1, Directory2
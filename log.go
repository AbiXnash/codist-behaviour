package behaviour

import "log/slog"

// logger is the program's logger, marked with this library's name. The program chooses the level
// and format; Codist logs at info unless CODIST_LOG=debug.
func logger() *slog.Logger { return slog.Default().With("lib", "behaviour") }

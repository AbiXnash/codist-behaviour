// Package behaviour runs the methods a commit changed on the code before and after it, with the same inputs, and
// reports what a caller would see differently. The code that makes the calls is a fixed Java harness carried in
// this package; inputs are standard edge cases for each parameter type. Nothing here writes tests or changes code:
// a person reads the differences and decides whether they were meant.
//
// It needs a JDK (java and javac, 17 or later) and the project's build tool to find its dependencies: the Maven
// wrapper (mvnw) or mvn. When something is missing it says so in the report instead of failing.
package behaviour

import (
	"bytes"
	"context"
	"crypto/sha1"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"

	git "github.com/AbiXnash/codist-git"
	java "github.com/AbiXnash/codist-java"
)

//go:embed Harness.java
var harnessSource []byte

// Difference is one call whose result differs between the old and the new code.
type Difference struct {
	Method string `json:"method"` // pkg.Class#name
	Call   string `json:"call"`   // the call as Java-like text, with the inputs
	Old    string `json:"old"`    // what the old code returned or threw, and its arguments after the call
	New    string `json:"new"`
}

// Report is what one commit's run found.
type Report struct {
	SHA         string       `json:"sha"`
	Status      string       `json:"status"` // done, skipped, failed
	Reason      string       `json:"reason,omitempty"`
	Module      string       `json:"module,omitempty"`
	Methods     []string     `json:"methods"`
	Calls       int          `json:"calls"`
	Ran         int          `json:"ran"`
	Differ      int          `json:"differ"`
	Flaky       int          `json:"flaky"`     // results that changed from run to run, not counted
	StateOnly   int          `json:"stateOnly"` // only private fields differed, not counted
	NullOnly    int          `json:"nullOnly"`  // a NullPointerException only because the harness passed null
	Differences []Difference `json:"differences"`
	Notes       []string     `json:"notes"`
	Took        float64      `json:"took"` // seconds
}

// Runner runs commits of one repository. Work is the folder for checkouts and builds, which it may delete.
type Runner struct {
	Repo    *git.Repo
	Work    string
	Timeout time.Duration // for one commit; default 10 minutes
	Ignored func(path string) bool
}

func skipped(sha, why string) Report {
	return Report{SHA: sha, Status: "skipped", Reason: why, Methods: []string{}, Differences: []Difference{}, Notes: []string{}}
}

// Run runs one commit and reports. It never returns an error: a commit it cannot run is "skipped" with a reason a
// person can act on, and one that breaks part way is "failed".
func (r *Runner) Run(ctx context.Context, sha string) Report {
	start := time.Now()
	rep := r.run(ctx, sha)
	rep.Took = time.Since(start).Round(time.Millisecond).Seconds()
	return rep
}

func (r *Runner) run(ctx context.Context, sha string) Report {
	c, err := r.Repo.Get(sha)
	if err != nil {
		return skipped(sha, "That commit is not in the repository.")
	}
	sha = c.SHA
	if len(c.Parents) == 0 {
		return skipped(sha, "The first commit has nothing before it to compare with.")
	}
	javaBin, javac, err := jdk()
	if err != nil {
		return skipped(sha, err.Error())
	}
	module, methods, err := r.Changed(sha)
	if err != nil {
		return skipped(sha, err.Error())
	}
	if len(methods) == 0 {
		return skipped(sha, "The commit changes no method in the project's main Java code.")
	}
	if r.Timeout == 0 {
		r.Timeout = 10 * time.Minute
	}
	ctx, cancel := context.WithTimeout(ctx, r.Timeout)
	defer cancel()

	dir := filepath.Join(r.Work, sha[:12])
	_ = os.RemoveAll(dir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return failed(sha, module, methods, err)
	}
	defer os.RemoveAll(dir)
	for _, v := range []struct{ name, ref string }{{"old", c.Parents[0]}, {"new", sha}} {
		src := filepath.Join(dir, "src-"+v.name)
		if err := r.export(ctx, v.ref, src); err != nil {
			return failed(sha, module, methods, err)
		}
		if err := build(ctx, javac, src, module, filepath.Join(dir, v.name)); err != nil {
			var setup setupError
			if errors.As(err, &setup) {
				return skipped(sha, err.Error())
			}
			if ctx.Err() != nil {
				return failed(sha, module, methods, fmt.Errorf("took longer than %s", r.Timeout))
			}
			msg := strings.ReplaceAll(err.Error(), src+string(os.PathSeparator), "") // paths as the project knows them
			return skipped(sha, fmt.Sprintf("The %s code of %s does not build here: %s", v.name, moduleName(module), msg))
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "methods.txt"), []byte(strings.Join(methods, "\n")+"\n"), 0o644); err != nil {
		return failed(sha, module, methods, err)
	}
	harness, err := buildHarness(ctx, javac, r.Work)
	if err != nil {
		return failed(sha, module, methods, err)
	}
	empty := filepath.Join(dir, "run") // the code under test may write files; keep them away from the project
	_ = os.MkdirAll(empty, 0o755)
	cmd := exec.CommandContext(ctx, javaBin, "-Xmx1g", "-cp", harness, "Harness", dir, "--json")
	cmd.Dir, cmd.Env = empty, cleanEnv(empty)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return failed(sha, module, methods, fmt.Errorf("took longer than %s", r.Timeout))
		}
		return failed(sha, module, methods, fmt.Errorf("the harness stopped: %v %s", err, lastLine(stderr.String())))
	}
	rep := Report{SHA: sha, Module: module, Methods: methods}
	if err := json.Unmarshal(lastJSON(stdout.Bytes()), &rep); err != nil {
		return failed(sha, module, methods, fmt.Errorf("unreadable harness output: %v", err))
	}
	rep.SHA, rep.Status, rep.Module, rep.Methods = sha, "done", module, methods
	if rep.Ran == 0 { // nothing could be compared: say why rather than "no difference"
		rep.Status, rep.Reason = "skipped", nothingRan(rep.Notes)
	}
	if rep.Differences == nil {
		rep.Differences = []Difference{}
	}
	if rep.Notes == nil {
		rep.Notes = []string{}
	}
	return rep
}

// nothingRan explains why no call could be compared, from the harness's notes.
func nothingRan(notes []string) string {
	added := true
	for _, n := range notes {
		added = added && (strings.HasPrefix(n, "no class ") || strings.HasSuffix(n, ": not in the old code"))
	}
	switch {
	case len(notes) > 0 && added:
		return "The commit adds this code, so there is no earlier version to compare with."
	case len(notes) > 0:
		return "Nothing could be compared: " + strings.Join(notes, "; ") + "."
	}
	return "Nothing could be compared: no inputs could be built for the changed methods."
}

func failed(sha, module string, methods []string, err error) Report {
	r := skipped(sha, err.Error())
	r.Status, r.Module, r.Methods = "failed", module, methods
	return r
}

func moduleName(dir string) string {
	if dir == "" {
		return "the project"
	}
	return filepath.Base(dir)
}

// export writes the repository's files at ref into dir.
func (r *Runner) export(ctx context.Context, ref, dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	archive := exec.CommandContext(ctx, "git", "-C", r.Repo.Root, "archive", "--format=tar", ref)
	var tarball bytes.Buffer
	archive.Stdout = &tarball
	if err := archive.Run(); err != nil {
		return fmt.Errorf("git archive %s: %w", ref, err)
	}
	return untar(&tarball, dir)
}

// keep is the environment the project's code may see. Everything else, such as cloud keys, database addresses
// and tokens, is left out, so code run from a commit cannot read them.
var keep = []string{"PATH", "JAVA_HOME", "LANG", "LC_ALL", "SystemRoot", "SYSTEMROOT", "WINDIR", "ComSpec", "PATHEXT"}

// cleanEnv is the environment for running the project's code: the variables in keep, with home and temp in dir.
func cleanEnv(dir string) []string {
	var env []string
	for _, k := range keep {
		if v, ok := os.LookupEnv(k); ok {
			env = append(env, k+"="+v)
		}
	}
	return append(env, "HOME="+dir, "USERPROFILE="+dir, "TMPDIR="+dir, "TEMP="+dir, "TMP="+dir)
}

// jdk finds java and javac: JAVA_HOME first, then the path.
func jdk() (string, string, error) {
	exe := ""
	if runtime.GOOS == "windows" {
		exe = ".exe"
	}
	if home := os.Getenv("JAVA_HOME"); home != "" {
		j, c := filepath.Join(home, "bin", "java"+exe), filepath.Join(home, "bin", "javac"+exe)
		if _, err := os.Stat(c); err == nil {
			return j, c, nil
		}
	}
	j, errJ := exec.LookPath("java")
	c, errC := exec.LookPath("javac")
	if errJ != nil || errC != nil {
		return "", "", errors.New("Running the code needs a JDK (java and javac, 17 or later). Install one, or set JAVA_HOME.")
	}
	return j, c, nil
}

var hunk = regexp.MustCompile(`^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@`)

// Changed lists the methods a commit changed, and the methods in the same module that call them, as
// "pkg.Class#name". It also names the module, the folder with the build file: one module per run.
func (r *Runner) Changed(sha string) (string, []string, error) {
	c, err := r.Repo.Get(sha)
	if err != nil || len(c.Parents) == 0 {
		return "", nil, errors.New("The commit has no parent to compare with.")
	}
	changes, err := r.Repo.Changes(c.SHA)
	if err != nil {
		return "", nil, err
	}
	files, _ := r.Repo.Files(c.SHA)
	found := map[string]bool{}
	modules := map[string]int{}
	for _, ch := range changes {
		p := ch.Path
		if !strings.HasSuffix(p, ".java") || strings.Contains(p, "/src/test/") || (r.Ignored != nil && r.Ignored(p)) {
			continue
		}
		old, added := oldLines(ch.Patch), ch.Added
		if ch.Status != "added" {
			if src, ok := r.Repo.FileAt(c.Parents[0], oldPath(ch)); ok {
				mark(found, oldPath(ch), src, old)
			}
		}
		if ch.Status != "deleted" {
			if src, ok := r.Repo.FileAt(c.SHA, p); ok {
				mark(found, p, src, added)
			}
		}
		modules[moduleOf(p)]++
	}
	if len(found) == 0 {
		return "", nil, nil
	}
	module, most := "", -1
	for m, n := range modules {
		if n > most || n == most && m < module {
			module, most = m, n
		}
	}
	for _, m := range r.callers(c.SHA, module, files, found) {
		found[m] = true
	}
	out := make([]string, 0, len(found))
	for m := range found {
		if strings.HasSuffix(m, "#<field>") {
			continue
		}
		out = append(out, m)
	}
	sort.Strings(out)
	return module, out, nil
}

func oldPath(ch git.FileChange) string {
	if ch.OldPath != "" {
		return ch.OldPath
	}
	return ch.Path
}

// oldLines lists the old-side line numbers a patch removes or replaces.
func oldLines(patch string) []int {
	var out []int
	o := 0
	for _, l := range strings.Split(patch, "\n") {
		switch {
		case strings.HasPrefix(l, "@@"):
			if m := hunk.FindStringSubmatch(l); m != nil {
				o, _ = strconv.Atoi(m[1])
			}
		case strings.HasPrefix(l, "---"), strings.HasPrefix(l, "+"), strings.HasPrefix(l, `\`):
		case strings.HasPrefix(l, "-"):
			out = append(out, o)
			o++
		default:
			o++
		}
	}
	return out
}

// mark adds the methods that contain the given lines of one file.
func mark(out map[string]bool, path string, src []byte, lines []int) {
	ix := java.Build(map[string][]byte{path: src}, nil)
	for _, l := range lines {
		t, m := ix.TypeAt(path, l)
		if t == nil {
			continue
		}
		name := "<field>"
		if m != nil {
			name = m.Name
		}
		out[binaryName(t, ix)+"#"+name] = true
	}
}

// binaryName is the name the JVM knows a class by: nested classes join with $.
func binaryName(t *java.Type, ix *java.Index) string {
	name := t.ID
	for _, o := range ix.Types {
		if o != t && strings.HasPrefix(t.ID, o.ID+".") && len(o.ID) < len(name) {
			name = o.ID + "$" + strings.ReplaceAll(t.ID[len(o.ID)+1:], ".", "$")
		}
	}
	return name
}

// moduleOf is the folder that holds a file's src/main/java, which Maven, Gradle and Codist's parser all take as
// the module; "" when it is the repository itself.
func moduleOf(path string) string {
	if i := strings.Index(path, "/src/main/java/"); i > 0 {
		return path[:i]
	}
	return ""
}

// callers lists the methods in the module that call a changed method, from Codist's call graph: a bug may only
// show when the changed method is reached the way the project reaches it.
func (r *Runner) callers(sha, module string, files []string, changed map[string]bool) []string {
	var paths []string
	for _, f := range files {
		if strings.HasSuffix(f, ".java") && !strings.Contains(f, "/src/test/") && (module == "" || strings.HasPrefix(f, module+"/")) {
			paths = append(paths, f)
		}
	}
	sources := r.Repo.ReadAll(sha, paths)
	ix := java.Build(sources, nil)
	name := func(file, class string) string {
		for _, t := range ix.Types {
			if t.File == file && t.Name == class {
				return binaryName(t, ix)
			}
		}
		return ""
	}
	var out []string
	for _, c := range java.BuildCallGraph(sources, ix.Types).Calls() {
		if changed[name(c.To.File, c.To.Class)+"#"+c.To.Name] && !c.From.Test {
			if from := name(c.From.File, c.From.Class); from != "" {
				out = append(out, from+"#"+c.From.Name)
			}
		}
	}
	return out
}

// setupError is something missing on this machine rather than wrong with the code.
type setupError struct{ msg string }

func (e setupError) Error() string { return e.msg }

// build compiles one version of the module into out/classes, with its dependencies listed in out/cp.txt. A Maven
// project is built by Maven, with the sibling modules it needs, so annotation processors such as Lombok work; a
// project with no build file is compiled with javac.
func build(ctx context.Context, javac, root, module, out string) error {
	dir := filepath.Join(root, filepath.FromSlash(module))
	for _, g := range []string{"build.gradle", "build.gradle.kts"} {
		if _, err := os.Stat(filepath.Join(dir, g)); err == nil {
			return setupError{"Gradle projects are not supported yet; only Maven."}
		}
	}
	if _, err := os.Stat(filepath.Join(dir, "pom.xml")); err != nil {
		return compile(ctx, javac, dir, "", out)
	}
	mvn := mavenIn(root, dir)
	if mvn == "" {
		return setupError{"Building the project needs Maven: the project's mvnw, or mvn on the path."}
	}
	const cpFile = "codist-classpath.txt" // written by each module into its own folder
	args := []string{"-q", "-B", "-Dmaven.test.skip=true", "-Dmdep.outputFile=" + cpFile}
	run := dir
	if _, err := os.Stat(filepath.Join(root, "pom.xml")); err == nil && module != "" {
		run = root // build from the top, so sibling modules this one uses are built too
		args = append(args, "-pl", module, "-am")
	}
	args = append(args, "compile", "dependency:build-classpath")
	cmd := exec.CommandContext(ctx, mvn, args...)
	cmd.Dir, cmd.Env = run, buildEnv()
	var output bytes.Buffer
	cmd.Stdout, cmd.Stderr = &output, &output
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return errors.New(mavenError(output.String()))
	}
	cp, err := os.ReadFile(filepath.Join(dir, cpFile))
	if err != nil {
		return fmt.Errorf("Maven did not list the dependencies: %v", err)
	}
	if err := copyTree(filepath.Join(dir, "target", "classes"), filepath.Join(out, "classes")); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(out, "cp.txt"), []byte(strings.TrimSpace(string(cp))), 0o644)
}

// mavenError is the first line of Maven's output that says what went wrong, without its links and advice.
func mavenError(out string) string {
	for _, l := range strings.Split(out, "\n") {
		l = strings.TrimSpace(l)
		if !strings.HasPrefix(l, "[ERROR]") {
			continue
		}
		msg := strings.TrimSpace(strings.TrimPrefix(l, "[ERROR]"))
		if msg == "" || strings.HasPrefix(msg, "->") || strings.HasPrefix(msg, "Re-run") || strings.HasPrefix(msg, "To see the full") ||
			strings.HasPrefix(msg, "For more information") || strings.HasPrefix(msg, "[Help") {
			continue
		}
		return msg
	}
	return lastLine(out)
}

// buildEnv is the environment for the build tool: the clean one, plus what Maven needs to find its settings, the
// local repository and a proxy.
func buildEnv() []string {
	var env []string
	for _, kv := range os.Environ() {
		k, _, _ := strings.Cut(kv, "=")
		switch {
		case k == "HOME", k == "USERPROFILE", k == "TMPDIR", k == "TEMP", k == "TMP",
			strings.HasPrefix(k, "MAVEN_"), strings.HasPrefix(k, "JAVA_"), strings.EqualFold(k, "M2_HOME"),
			strings.EqualFold(k, "http_proxy"), strings.EqualFold(k, "https_proxy"), strings.EqualFold(k, "no_proxy"):
			env = append(env, kv)
		}
	}
	for _, k := range keep {
		if v, ok := os.LookupEnv(k); ok && k != "JAVA_HOME" {
			env = append(env, k+"="+v)
		}
	}
	return env
}

// mavenIn finds the Maven wrapper in the module or a folder above it within the project, or mvn on the path.
func mavenIn(root, dir string) string {
	names := []string{"mvnw"}
	if runtime.GOOS == "windows" {
		names = []string{"mvnw.cmd"}
	}
	for d := dir; ; d = filepath.Dir(d) {
		for _, n := range names {
			p := filepath.Join(d, n)
			if st, err := os.Stat(p); err == nil && !st.IsDir() {
				if runtime.GOOS != "windows" {
					_ = os.Chmod(p, st.Mode()|0o111)
				}
				return p
			}
		}
		if rel, err := filepath.Rel(root, d); err != nil || rel == "." || strings.HasPrefix(rel, "..") {
			break
		}
	}
	if p, err := exec.LookPath("mvn"); err == nil {
		return p
	}
	return ""
}

// compile builds a module's main code into out/classes, with its resources, and writes out/cp.txt. It tries newer
// Java releases until one compiles, since projects target different versions.
func compile(ctx context.Context, javac, module, cp, out string) error {
	src := filepath.Join(module, "src", "main", "java")
	var files []string
	_ = filepath.WalkDir(src, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() && strings.HasSuffix(p, ".java") {
			files = append(files, p)
		}
		return nil
	})
	if len(files) == 0 {
		return errors.New("no main Java sources")
	}
	classes := filepath.Join(out, "classes")
	list := filepath.Join(out, "files.txt")
	if err := os.MkdirAll(classes, 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(list, []byte(quoteArgs(files)), 0o644); err != nil {
		return err
	}
	var last string
	for _, release := range []string{"8", "11", "17", "21"} {
		cmd := exec.CommandContext(ctx, javac, "--release", release, "-nowarn", "-encoding", "UTF-8", "-proc:none", "-cp", cp, "-d", classes, "@"+list)
		var stderr bytes.Buffer
		cmd.Stderr = &stderr
		if err := cmd.Run(); err == nil {
			_ = copyTree(filepath.Join(module, "src", "main", "resources"), classes)
			return os.WriteFile(filepath.Join(out, "cp.txt"), []byte(cp), 0o644)
		} else if ctx.Err() != nil {
			return ctx.Err()
		}
		last = firstError(stderr.String())
	}
	return errors.New(last)
}

// buildHarness compiles the embedded harness once per version of it.
func buildHarness(ctx context.Context, javac, work string) (string, error) {
	sum := sha1.Sum(harnessSource)
	dir := filepath.Join(work, "harness-"+hex.EncodeToString(sum[:6]))
	if _, err := os.Stat(filepath.Join(dir, "Harness.class")); err == nil {
		return dir, nil
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	src := filepath.Join(dir, "Harness.java")
	if err := os.WriteFile(src, harnessSource, 0o644); err != nil {
		return "", err
	}
	cmd := exec.CommandContext(ctx, javac, "-nowarn", "-d", dir, src)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("compiling the harness needs Java 17 or later: %s", firstError(stderr.String()))
	}
	return dir, nil
}

// quoteArgs writes a javac argument file: one quoted path per line, backslashes doubled.
func quoteArgs(paths []string) string {
	var b strings.Builder
	for _, p := range paths {
		b.WriteString(`"` + strings.ReplaceAll(p, `\`, `\\`) + "\"\n")
	}
	return b.String()
}

func firstError(s string) string {
	for _, l := range strings.Split(s, "\n") {
		if strings.Contains(l, "error") {
			return strings.TrimSpace(l)
		}
	}
	return lastLine(s)
}

func lastLine(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return strings.TrimSpace(lines[len(lines)-1])
}

// lastJSON is the last line of output that is a JSON object; the code under test may have printed before it.
func lastJSON(b []byte) []byte {
	lines := bytes.Split(bytes.TrimSpace(b), []byte("\n"))
	for i := len(lines) - 1; i >= 0; i-- {
		if bytes.HasPrefix(bytes.TrimSpace(lines[i]), []byte("{")) {
			return lines[i]
		}
	}
	return b
}

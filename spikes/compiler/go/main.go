package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"

	"go.yaml.in/yaml/v3"
)

type object = map[string]any

type evidence struct {
	SchemaVersion string
	Application   struct{ ID string }
	Adapter       struct{ ID string }
	Services      []struct{ ID, Module string }
	Findings      []struct{ ID, ServiceID, Capability, Implementation string }
	Diagnostics   []object
}

type service struct {
	ImageRef string `yaml:"imageRef"`
	Port     int
}

type postgres struct {
	ID, Management, Database string
	SecretRef                string `yaml:"secretRef"`
	StorageSize              string `yaml:"storageSize"`
	EndpointRef              string `yaml:"endpointRef"`
}

type declaration struct {
	SchemaVersion string `yaml:"schemaVersion"`
	ApplicationID string `yaml:"applicationId"`
	Services      map[string]service
	Postgres      *postgres
	RequireHA     bool `yaml:"requireHostFailover"`
}

var name = regexp.MustCompile(`^[a-z][a-z0-9-]{0,38}[a-z0-9]$|^[a-z]$`)
var endpoint = regexp.MustCompile(`^[A-Z_][A-Z0-9_]*$`)
var size = regexp.MustCompile(`^[1-9][0-9]*Gi$`)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(args []string) error {
	if len(args) != 4 || (args[0] != "docker-compose" && args[0] != "kubernetes") {
		return errors.New("E_USAGE")
	}
	target := args[0]
	rawEvidence, err := os.ReadFile(args[1])
	if err != nil {
		return errors.New("E_INPUT")
	}
	rawDeclaration, err := os.ReadFile(args[2])
	if err != nil {
		return errors.New("E_INPUT")
	}
	var e evidence
	var d declaration
	if json.Unmarshal(rawEvidence, &e) != nil || yaml.Unmarshal(rawDeclaration, &d) != nil {
		return errors.New("E_INPUT")
	}
	if e.SchemaVersion != "evidence/v1alpha1" || d.SchemaVersion != "infra/v1alpha1" {
		return errors.New("E_VERSION")
	}
	db := d.Postgres
	if db == nil || (db.Management != "managed" && db.Management != "external") {
		return errors.New("E_OWNERSHIP")
	}
	if !name.MatchString(db.SecretRef) {
		return errors.New("E_SECRET_REF")
	}
	if db.Management == "external" && !endpoint.MatchString(db.EndpointRef) {
		return errors.New("E_ENDPOINT_REF")
	}
	if d.RequireHA {
		return errors.New("E_CAPABILITY")
	}
	if !name.MatchString(d.ApplicationID) || e.Application.ID != d.ApplicationID || e.Adapter.ID == "" ||
		!name.MatchString(db.ID) || !name.MatchString(db.Database) ||
		(db.Management == "managed" && !size.MatchString(db.StorageSize)) ||
		len(e.Services) == 0 || len(e.Services) != len(d.Services) {
		return errors.New("E_DECLARATION")
	}
	modules := map[string]string{}
	ids := make([]string, 0, len(e.Services))
	for _, s := range e.Services {
		runtime := d.Services[s.ID]
		if !name.MatchString(s.ID) || s.ID == db.ID || s.Module == "" || modules[s.ID] != "" ||
			runtime.ImageRef == "" || runtime.Port < 1 || runtime.Port > 65535 {
			return errors.New("E_DECLARATION")
		}
		modules[s.ID] = s.Module
		ids = append(ids, s.ID)
	}
	sort.Strings(ids)
	findingIDs := []string{}
	relationalServices := map[string]bool{}
	seenFindings := map[string]bool{}
	for _, f := range e.Findings {
		if f.ID == "" || seenFindings[f.ID] || modules[f.ServiceID] == "" {
			return errors.New("E_EVIDENCE")
		}
		seenFindings[f.ID] = true
		if f.Capability == "database.relational" {
			if f.Implementation != "" && f.Implementation != "postgresql" {
				return errors.New("E_CONFLICT")
			}
			relationalServices[f.ServiceID] = true
			findingIDs = append(findingIDs, f.ID)
		}
	}
	sort.Strings(findingIDs)
	if len(relationalServices) != len(ids) {
		return errors.New("E_EVIDENCE")
	}
	model, plan, path, artifact := compile(target, e, d, ids, modules, findingIDs)
	if err := os.Mkdir(args[3], 0755); err != nil {
		return errors.New("E_OUTPUT")
	}
	for _, file := range []struct {
		name string
		data object
	}{{"application.json", model}, {"plan.json", plan}, {path, artifact}} {
		bytes, err := json.MarshalIndent(file.data, "", "  ")
		if err != nil || os.WriteFile(filepath.Join(args[3], file.name), append(bytes, '\n'), 0644) != nil {
			return errors.New("E_OUTPUT")
		}
	}
	return nil
}

func compile(target string, e evidence, d declaration, ids []string, modules map[string]string, findingIDs []string) (object, object, string, object) {
	db := d.Postgres
	managed := db.Management == "managed"
	connection := object{"secretRef": db.SecretRef}
	settings := object{"database": db.Database}
	action, risk := "reference", "low"
	if managed {
		settings["storage"] = object{"size": db.StorageSize}
		action, risk = "create", "moderate"
	} else {
		connection["endpointRef"] = db.EndpointRef
	}
	services, bindings := []object{}, []object{}
	ops := []object{operation(db.ID, action, risk, "Resolve PostgreSQL ownership.")}
	for _, id := range ids {
		s := d.Services[id]
		services = append(services, object{"id": id, "source": object{"adapter": e.Adapter.ID, "module": modules[id]},
			"build": object{"strategy": "prebuilt", "imageRef": s.ImageRef}, "runtime": object{"port": s.Port}})
		bindings = append(bindings, object{"serviceId": id, "resourceId": db.ID, "contract": "spring.datasource"})
		ops = append(ops, operation(id, "bind", "low", "Wire the application to PostgreSQL."))
	}
	model := object{"schemaVersion": "application/v1alpha1", "application": object{"id": d.ApplicationID},
		"services": services, "resources": []object{{"id": db.ID, "kind": "postgresql", "management": db.Management,
			"deletionPolicy": "retain", "settings": settings, "connection": connection}},
		"bindings": bindings, "provenance": []object{{"resourceId": db.ID, "findingIds": findingIDs, "declarationRef": "infra.yaml#/postgres"}}}
	path, kind := "compose.yaml", "compose-manifest"
	prereqs := []object{prerequisite("oci-image", "Provide the prebuilt application image.")}
	limitations := []string{"Compilation preview only."}
	var artifact object
	if target == "docker-compose" {
		prereqs = append(prereqs, prerequisite("credentials", "Provide the referenced local credentials env file."))
		if managed {
			limitations = append(limitations, "Compose does not enforce the declared storage size.")
		} else {
			prereqs = append(prereqs, prerequisite("endpoint", "Provide the external JDBC URL through the referenced environment variable."))
		}
		limitations = append(limitations, "Single-host Compose cannot provide host failover.")
		artifact = compose(d, ids)
	} else {
		path, kind = "kubernetes.yaml", "kubernetes-manifest"
		prereqs = append(prereqs, prerequisite("credentials", "Provide the referenced Kubernetes Secret."))
		if managed {
			prereqs = append(prereqs, prerequisite("cloudnativepg", "Install CloudNativePG and configure compatible storage."))
		}
		artifact = kubernetes(d, ids)
	}
	diagnostics := e.Diagnostics
	if diagnostics == nil {
		diagnostics = []object{}
	}
	plan := object{"schemaVersion": "plan/v1alpha1", "applicationId": d.ApplicationID,
		"target": object{"backend": target, "environment": "dev"}, "mode": "compile-preview",
		"operations": ops, "prerequisites": prereqs, "artifacts": []object{{"path": path, "kind": kind}},
		"diagnostics": diagnostics, "limitations": limitations}
	return model, plan, path, artifact
}

func operation(id, action, risk, description string) object {
	return object{"id": action + "-" + id, "resourceId": id, "action": action, "risk": risk, "description": description, "requiresApproval": false}
}

func prerequisite(id, description string) object {
	return object{"id": id, "description": description, "required": true}
}

func jdbc(host, database string) string {
	return "jdbc:postgresql://" + host + ":5432/" + database
}

func compose(d declaration, ids []string) object {
	db := d.Postgres
	credentials := []string{"./secrets/" + db.SecretRef + ".env"}
	url := "$" + "{" + db.EndpointRef + ":?set external JDBC URL}"
	services := object{}
	result := object{"name": d.ApplicationID, "services": services}
	if db.Management == "managed" {
		url = jdbc(db.ID, db.Database)
		services[db.ID] = object{"image": "postgres:16", "env_file": credentials,
			"environment": object{"POSTGRES_DB": db.Database}, "volumes": []string{db.ID + ":/var/lib/postgresql/data"}}
		result["volumes"] = object{db.ID: object{}}
	}
	for _, id := range ids {
		s := d.Services[id]
		services[id] = object{"image": s.ImageRef, "env_file": credentials, "expose": []int{s.Port},
			"environment": object{"SPRING_DATASOURCE_URL": url}}
	}
	return result
}

func kubernetes(d declaration, ids []string) object {
	db := d.Postgres
	secretEnv := func(name, key string) object {
		return object{"name": name, "valueFrom": object{"secretKeyRef": object{"name": db.SecretRef, "key": key}}}
	}
	items := []object{}
	if db.Management == "managed" {
		items = append(items, object{"apiVersion": "postgresql.cnpg.io/v1", "kind": "Cluster",
			"metadata": object{"name": db.ID}, "spec": object{"instances": 1,
				"storage": object{"size": db.StorageSize}, "bootstrap": object{"initdb": object{
					"database": db.Database, "owner": "app", "secret": object{"name": db.SecretRef}}}}})
	}
	for _, id := range ids {
		s := d.Services[id]
		labels := object{"app": id}
		url := secretEnv("SPRING_DATASOURCE_URL", db.EndpointRef)
		if db.Management == "managed" {
			url = object{"name": "SPRING_DATASOURCE_URL", "value": jdbc(db.ID+"-rw", db.Database)}
		}
		container := object{"name": id, "image": s.ImageRef, "ports": []object{{"containerPort": s.Port}},
			"env": []object{url, secretEnv("SPRING_DATASOURCE_USERNAME", "username"), secretEnv("SPRING_DATASOURCE_PASSWORD", "password")}}
		items = append(items, object{"apiVersion": "apps/v1", "kind": "Deployment", "metadata": object{"name": id},
			"spec": object{"replicas": 1, "selector": object{"matchLabels": labels},
				"template": object{"metadata": object{"labels": labels}, "spec": object{"containers": []object{container}}}}},
			object{"apiVersion": "v1", "kind": "Service", "metadata": object{"name": id},
				"spec": object{"selector": labels, "ports": []object{{"port": s.Port, "targetPort": s.Port}}}})
	}
	return object{"apiVersion": "v1", "kind": "List", "items": items}
}

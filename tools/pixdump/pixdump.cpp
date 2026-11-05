// pixdump: exports the constant buffers bound at each draw and dispatch of a PIX GPU capture (.wpix), with the
// shaders bound there (by DXIL hash), to a JSON file next to the capture. Bedrock Visuals matches the hashes to
// Bedrock's material files to name every value, so nobody has to read them out of the PIX window.
//
// Usage: pixdump <capture.wpix> [--all] [--max-per-pipeline N]
//   By default the first 3 draws of each distinct shader combination are exported (Bedrock draws terrain
//   thousands of times with the same shaders); --all exports every draw.
//
// Uses the PIX API (pixapi.dll from the PIX install, found under C:\Program Files\Microsoft PIX), which replays
// the capture on this PC's GPU to read the buffers.

#include <windows.h>
#include <d3d12.h>
#include "PixApi.h"
#include "PixApiD3DStateInterfaces.h"
#include "PixApiCaptureFileConverterInterfaces.h"
#include "PixApiUuids.h"

#include <cstdio>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace {

template <typename T>
struct Ptr {
	T* p = nullptr;
	Ptr() = default;
	Ptr(const Ptr&) = delete;
	Ptr& operator=(const Ptr&) = delete;
	~Ptr() { if (p) p->Release(); }
	T* operator->() const { return p; }
	T** out() { if (p) { p->Release(); p = nullptr; } return &p; }
	void** vout() { return reinterpret_cast<void**>(out()); }
	explicit operator bool() const { return p != nullptr; }
};

std::wstring g_log;

void fail(const char* what, HRESULT hr) {
	fprintf(stderr, "pixdump: %s failed (0x%08lX)\n", what, static_cast<unsigned long>(hr));
}

std::string utf8(LPCWSTR w) {
	if (!w) return {};
	int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
	std::string s(n > 0 ? n - 1 : 0, '\0');
	if (n > 1) WideCharToMultiByte(CP_UTF8, 0, w, -1, s.data(), n, nullptr, nullptr);
	return s;
}

std::string json(const std::string& s) {
	std::string o = "\"";
	for (unsigned char c : s) {
		if (c == '"' || c == '\\') { o += '\\'; o += static_cast<char>(c); }
		else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
		else o += static_cast<char>(c);
	}
	return o + "\"";
}

std::string hex(const BYTE* b, size_t n) {
	static const char* d = "0123456789abcdef";
	std::string s;
	s.reserve(n * 2);
	for (size_t i = 0; i < n; i++) { s += d[b[i] >> 4]; s += d[b[i] & 15]; }
	return s;
}

const char* stageName(PIX_SHADER_STAGE s) {
	switch (s) {
	case PIX_SHADER_STAGE_VERTEX: return "vertex";
	case PIX_SHADER_STAGE_PIXEL: return "pixel";
	case PIX_SHADER_STAGE_COMPUTE: return "compute";
	case PIX_SHADER_STAGE_GEOMETRY: return "geometry";
	case PIX_SHADER_STAGE_HULL: return "hull";
	case PIX_SHADER_STAGE_DOMAIN: return "domain";
	case PIX_SHADER_STAGE_MESH: return "mesh";
	case PIX_SHADER_STAGE_AMPLIFICATION: return "amplification";
	default: return "other";
	}
}

bool isWork(const char* name) {
	if (!name) return false;
	return strstr(name, "Draw") || strstr(name, "Dispatch") || strstr(name, "ExecuteIndirect");
}

// Finds pixapi.dll under C:\Program Files\Microsoft PIX (the newest version folder that has it).
std::wstring findPixApi() {
	wchar_t pf[MAX_PATH];
	if (!GetEnvironmentVariableW(L"ProgramFiles", pf, MAX_PATH)) wcscpy(pf, L"C:\\Program Files");
	std::wstring root = std::wstring(pf) + L"\\Microsoft PIX\\";
	std::wstring best;
	WIN32_FIND_DATAW fd;
	HANDLE h = FindFirstFileW((root + L"*").c_str(), &fd);
	if (h == INVALID_HANDLE_VALUE) return {};
	do {
		if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) || fd.cFileName[0] == L'.') continue;
		std::wstring dir = root + fd.cFileName;
		for (const wchar_t* sub : {L"\\", L"\\bin\\", L"\\x64\\", L"\\lib\\"}) {
			std::wstring candidate = dir + sub + L"pixapi.dll";
			if (GetFileAttributesW(candidate.c_str()) != INVALID_FILE_ATTRIBUTES && candidate > best) best = candidate;
		}
	} while (FindNextFileW(h, &fd));
	FindClose(h);
	return best;
}

struct Binding {
	std::string stage;
	int reg = -1;
	int space = -1;
	std::string name;
};

std::vector<Binding> bindingsOf(IPixResourceView* view) {
	std::vector<Binding> out;
	Ptr<IPixResourceViewBindings> bindings;
	if (FAILED(view->GetBindings(__uuidof(IPixResourceViewBindings), bindings.vout())) || !bindings) return out;
	for (UINT32 i = 0; i < bindings->GetCount(); i++) {
		PIX_RESOURCE_BINDING_TYPE type;
		if (FAILED(bindings->GetViewType(i, &type))) continue;
		Binding b;
		if (type == PIX_RESOURCE_BINDING_PROGRAM_BOUND) {
			Ptr<IPixProgramResourceBinding> pb;
			if (SUCCEEDED(bindings->GetViewBinding(i, __uuidof(IPixProgramResourceBinding), pb.vout())) && pb) {
				PIX_GENERIC_PIPELINE_STAGE st;
				if (SUCCEEDED(pb->GetGenericPipelineStage(&st))) b.stage = std::to_string(static_cast<int>(st));
				b.reg = static_cast<int>(pb->GetBindingIndex());
			}
		} else if (type == PIX_RESOURCE_BINDING_ROOT_PARAMETER_BOUND) {
			Ptr<IPixRootParameterResourceBinding> rb;
			if (SUCCEEDED(bindings->GetViewBinding(i, __uuidof(IPixRootParameterResourceBinding), rb.vout())) && rb) {
				PIX_GENERIC_PIPELINE_STAGE st;
				if (SUCCEEDED(rb->GetGenericPipelineStage(&st))) b.stage = std::to_string(static_cast<int>(st));
				b.reg = static_cast<int>(rb->GetIndex());
				b.space = static_cast<int>(rb->GetSpace());
				b.name = utf8(rb->GetName());
			}
		} else {
			b.stage = "binding type " + std::to_string(static_cast<int>(type));
		}
		out.push_back(b);
	}
	return out;
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
	if (argc < 2) {
		fprintf(stderr, "usage: pixdump <capture.wpix> [--all] [--max-per-pipeline N]\n");
		return 2;
	}
	std::wstring capture = argv[1];
	int maxPerPipeline = 3;
	for (int i = 2; i < argc; i++) {
		if (!wcscmp(argv[i], L"--all")) maxPerPipeline = 1 << 30;
		else if (!wcscmp(argv[i], L"--max-per-pipeline") && i + 1 < argc) maxPerPipeline = _wtoi(argv[++i]);
	}

	std::wstring dll = findPixApi();
	if (dll.empty()) {
		fprintf(stderr, "pixdump: no pixapi.dll under C:\\Program Files\\Microsoft PIX. Is PIX installed?\n");
		return 1;
	}
	std::wstring dllDir = dll.substr(0, dll.find_last_of(L'\\'));
	SetDllDirectoryW(dllDir.c_str());
	HMODULE module = LoadLibraryExW(dll.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
	if (!module) {
		fprintf(stderr, "pixdump: couldn't load %s (error %lu)\n", utf8(dll.c_str()).c_str(), GetLastError());
		return 1;
	}
	printf("PIX API: %s\n", utf8(dll.c_str()).c_str());
	auto create = reinterpret_cast<HRESULT(WINAPI*)(REFIID, void**)>(GetProcAddress(module, "PixCreateFactory"));
	if (!create) { fprintf(stderr, "pixdump: pixapi.dll has no PixCreateFactory\n"); return 1; }

	CoInitializeEx(nullptr, COINIT_MULTITHREADED);
	HRESULT hr;
	Ptr<IPixFactory> factory;
	if (FAILED(hr = create(__uuidof(IPixFactory), factory.vout()))) { fail("PixCreateFactory", hr); return 1; }

	// Captures from PIX versions before 2026 use the old file format, which the API can't open directly: upgrade
	// a copy first.
	{
		Ptr<IPixCaptureFileConverter> converter;
		if (SUCCEEDED(factory->CreatePixCaptureFileConverter(__uuidof(IPixCaptureFileConverter), converter.vout())) && converter) {
			PIX_GPU_CAPTURE_FILE_FORMAT format = converter->GetGpuCaptureFileFormat(capture.c_str());
			const char* names[] = {"missing", "not a recognised PIX GPU capture (or corrupt)", "pre-2026", "2026"};
			printf("capture file format: %s\n", format <= 3 ? names[format] : "unknown");
			if (format == PIX_GPU_CAPTURE_FILE_FORMAT_NO_FILE || format == PIX_GPU_CAPTURE_FILE_FORMAT_INVALID_OR_CORRUPT) {
				fprintf(stderr, "pixdump: PIX can't read that file as a GPU capture\n");
				return 1;
			}
			if (format == PIX_GPU_CAPTURE_FILE_FORMAT_PRE2026) {
				std::wstring upgraded = capture.substr(0, capture.size() - (capture.size() >= 5 ? 5 : 0)) + L".upgraded.wpix";
				printf("upgrading it to the current format: %s\n", utf8(upgraded.c_str()).c_str());
				if (FAILED(hr = converter->UpgradeGpuCaptureFile(capture.c_str(), upgraded.c_str(), nullptr))) {
					fail("upgrading the capture", hr);
					return 1;
				}
				capture = upgraded;
			}
		}
	}

	Ptr<IPixGpuCaptureDocument> doc;
	if (FAILED(hr = factory->OpenGpuCaptureDocument(capture.c_str(), nullptr, nullptr, __uuidof(IPixGpuCaptureDocument), doc.vout()))) {
		fail("opening the capture", hr);
		fprintf(stderr, "  (0x8ABC000B is PIX's 'invalid file'. Try opening the capture once in PIX 2610.28 and saving it again.)\n");
		return 1;
	}
	printf("opened %s\n", utf8(capture.c_str()).c_str());

	Ptr<IPixGpuCaptureAnalysis> analysis;
	if (FAILED(hr = doc->GetAnalysis(__uuidof(IPixGpuCaptureAnalysis), analysis.vout()))) { fail("GetAnalysis", hr); return 1; }
	PIX_CONNECTION_DESC_LOCAL local{};
	PIX_CONNECTION_DESC connection{};
	connection.Type = PIX_CONNECTION_TYPE_LOCAL;
	connection.pLocal = &local;
	if (FAILED(hr = analysis->Connect(&connection, nullptr))) { fail("connecting to this PC for analysis", hr); return 1; }
	PIX_ANALYSIS_PARAMS params{};
	params.Adapter = PIX_ADAPTER_ID_DEFAULT;
	params.Flags = PIX_ANALYSIS_FLAG_NONE;
	{
		Ptr<IPixAdapters> adapters;
		if (SUCCEEDED(analysis->GetAdapters(__uuidof(IPixAdapters), adapters.vout())) && adapters) {
			for (UINT64 i = 0; i < adapters->GetCount(); i++) {
				PIX_ADAPTER a{};
				if (SUCCEEDED(adapters->GetAdapter(i, &a))) printf("adapter %llu: %s\n", (unsigned long long)a.Id, utf8(a.Name).c_str());
			}
		}
	}
	printf("starting analysis (PIX replays the capture on this GPU; this can take a minute)...\n");
	if (FAILED(hr = analysis->StartAnalysis(&params, nullptr, nullptr))) { fail("StartAnalysis", hr); return 1; }

	std::wstring outPath = capture + L".bvdump.json";
	FILE* out = _wfopen(outPath.c_str(), L"wb");
	if (!out) { fprintf(stderr, "pixdump: can't write %s\n", utf8(outPath.c_str()).c_str()); return 1; }
	fprintf(out, "{\"capture\":%s,\"draws\":[\n", json(utf8(capture.c_str())).c_str());

	Ptr<IPixCollection> queues;
	if (FAILED(hr = doc->GetQueues(__uuidof(IPixCollection), queues.vout()))) { fail("GetQueues", hr); return 1; }
	std::map<std::string, int> perPipeline;
	bool first = true;
	int exported = 0, seen = 0, failures = 0;
	for (UINT64 q = 0; q < queues->GetCount(); q++) {
		Ptr<IPixGpuCaptureQueueInfo> queue;
		if (FAILED(queues->Get(q, __uuidof(IPixGpuCaptureQueueInfo), queue.vout())) || !queue) continue;
		UINT32 count = queue->GetEventCount();
		printf("queue %s: %u events\n", utf8(queue->GetName()).c_str(), count);
		std::vector<PIX_EVENT_INFO> events(count);
		for (UINT32 e = 0; e < count; e++) queue->GetEvent(e, &events[e]);
		std::map<UINT32, UINT32> byIndex;
		for (UINT32 e = 0; e < count; e++) byIndex[events[e].Index] = e;

		for (UINT32 e = 0; e < count; e++) {
			PIX_EVENT_INFO& ev = events[e];
			if (!isWork(ev.Name)) continue;
			seen++;
			Ptr<IPixProgramState> state;
			if (FAILED(doc->GetProgramState(&ev, __uuidof(IPixProgramState), state.vout())) || !state) continue;
			Ptr<IPixGenericPipeline> pipeline;
			if (FAILED(state->GetGpuProgram(__uuidof(IPixGenericPipeline), pipeline.vout())) || !pipeline) continue;

			// Shaders: stage and DXIL hash.
			std::string shadersJson = "[";
			std::string key;
			Ptr<IPixCollection> shaders;
			if (SUCCEEDED(pipeline->GetShaders(__uuidof(IPixCollection), shaders.vout())) && shaders) {
				for (UINT64 s = 0; s < shaders->GetCount(); s++) {
					Ptr<IPixShader> shader;
					if (FAILED(shaders->Get(s, __uuidof(IPixShader), shader.vout())) || !shader) continue;
					std::vector<BYTE> hash(shader->GetHashSizeBytes());
					if (!hash.empty()) shader->GetHash(static_cast<UINT32>(hash.size()), hash.data());
					std::string h = hex(hash.data(), hash.size());
					key += h + ";";
					if (shadersJson.size() > 1) shadersJson += ",";
					shadersJson += "{\"stage\":\"" + std::string(stageName(shader->GetStage())) + "\",\"hash\":\"" + h
							+ "\",\"entry\":" + json(utf8(shader->GetEntryPoint())) + "}";
				}
			}
			shadersJson += "]";
			if (perPipeline[key]++ >= maxPerPipeline) continue;

			// Enclosing markers, outermost first.
			std::vector<std::string> markers;
			UINT32 parent = ev.ParentIndex;
			for (int guard = 0; guard < 64; guard++) {
				auto it = byIndex.find(parent);
				if (it == byIndex.end() || it->second == e) break;
				const PIX_EVENT_INFO& p = events[it->second];
				markers.insert(markers.begin(), p.Name ? p.Name : "");
				if (p.ParentIndex == parent) break;
				parent = p.ParentIndex;
			}
			std::string markersJson = "[";
			for (size_t m = 0; m < markers.size(); m++) markersJson += (m ? "," : "") + json(markers[m]);
			markersJson += "]";

			// Constant buffers bound here.
			std::string buffersJson = "[";
			Ptr<IPixResourceViewsAtEvent> views;
			if (SUCCEEDED(pipeline->GetResourceViews(__uuidof(IPixResourceViewsAtEvent), views.vout())) && views) {
				for (UINT32 v = 0; v < views->GetCount(); v++) {
					PIX_RESOURCE_VIEW_TYPE type;
					if (FAILED(views->GetType(v, &type))) continue;
					if (type != PIX_RESOURCE_CONSTANT_BUFFER_VIEW && type != PIX_RESOURCE_ROOT_CONSTANT) continue;
					Ptr<IPixResourceView> view;
					if (FAILED(views->GetView(v, __uuidof(IPixResourceView), view.vout())) || !view) continue;
					std::string entry = "{\"type\":" + std::to_string(static_cast<int>(type)) + ",\"bindings\":[";
					std::vector<Binding> bs = bindingsOf(view.p);
					for (size_t b = 0; b < bs.size(); b++) {
						entry += std::string(b ? "," : "") + "{\"stage\":" + json(bs[b].stage) + ",\"register\":"
								+ std::to_string(bs[b].reg) + ",\"space\":" + std::to_string(bs[b].space) + ",\"name\":"
								+ json(bs[b].name) + "}";
					}
					entry += "]";
					PIX_GET_BUFFER_BYTES_PARAMETERS bp{};
					bp.BeforeEvent = TRUE;  // as the draw sees them
					Ptr<IPixBlob> blob;
					hr = analysis->GetBufferBytesFromResourceView(view.p, &ev, &bp, __uuidof(IPixBlob), blob.vout());
					if (SUCCEEDED(hr) && blob) {
						std::vector<BYTE> bytes(blob->GetSizeBytes());
						if (!bytes.empty()) blob->GetData(bytes.size(), bytes.data());
						// The view window starts at the view's offset into the resource.
						UINT64 offset = 0;
						Ptr<IPixConstantBufferView> cbv;
						if (SUCCEEDED(view->QueryInterface(__uuidof(IPixConstantBufferView), cbv.vout())) && cbv) {
							const D3D12_CONSTANT_BUFFER_VIEW_DESC* desc = nullptr;
							offset = cbv->GetBufferLocationOffset();
							if (SUCCEEDED(cbv->GetDesc(&desc)) && desc && offset + desc->SizeInBytes <= bytes.size()) {
								bytes = std::vector<BYTE>(bytes.begin() + offset, bytes.begin() + offset + desc->SizeInBytes);
							}
						}
						entry += ",\"offset\":" + std::to_string(offset) + ",\"bytes\":\"" + hex(bytes.data(), bytes.size()) + "\"";
					} else {
						failures++;
						char b[32];
						snprintf(b, sizeof b, "0x%08lX", static_cast<unsigned long>(hr));
						entry += ",\"error\":\"" + std::string(b) + "\"";
					}
					entry += "}";
					if (buffersJson.size() > 1) buffersJson += ",";
					buffersJson += entry;
				}
			}
			buffersJson += "]";

			fprintf(out, "%s{\"queue\":%llu,\"event\":%u,\"name\":%s,\"markers\":%s,\"shaders\":%s,\"buffers\":%s}\n",
					first ? "" : ",", (unsigned long long)q, ev.Index, json(ev.Name ? ev.Name : "").c_str(),
					markersJson.c_str(), shadersJson.c_str(), buffersJson.c_str());
			first = false;
			exported++;
			if (exported % 50 == 0) printf("  %d draws exported...\n", exported);
		}
	}
	fprintf(out, "]}\n");
	fclose(out);
	analysis->StopAnalysis();
	analysis->Disconnect();
	printf("done: %d of %d draws exported (%zu shader combinations), %d buffer reads failed\nwrote %s\n", exported, seen,
			perPipeline.size(), failures, utf8(outPath.c_str()).c_str());
	return 0;
}

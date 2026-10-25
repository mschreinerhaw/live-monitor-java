import copy
import hashlib
import io
import json
from pathlib import Path
import socket
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import agent
from installer import Installer
from stack_ops import StackOperations


class InstallerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys=tempfile.TemporaryDirectory();root=Path(cls.keys.name)
        cls.private=root/'signing.pem';cls.public=root/'trust.pem'
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(cls.private)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['openssl','pkey','-in',str(cls.private),'-pubout','-out',str(cls.public)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

    @classmethod
    def tearDownClass(cls):cls.keys.cleanup()

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.a=agent.Agent({'environment':'test','state_root':str(self.root/'state'),'backup_root':str(self.root/'backups'),'secrets_root':str(self.root/'secrets'),'allow_image_import':True,'allowed_registries':[], 'install_policy':{'enabled':True,'products':['runtime-os-test'],'trusted_public_keys':[str(self.public)],'capabilities':[]}})
        self.installer=Installer(self.a)
        self.image='sha256:'+'a'*64
        self.doc={'format':'live-monitor.install.v1','product':'runtime-os-test','version':'test-1','files':{'images/test.tar':hashlib.sha256(b'image-fixture').hexdigest()},'images':[{'file':'images/test.tar','ids':[self.image]}], 'parameters':{'http_port':{'type':'integer','default':18899,'min':1024,'max':65535},'token':{'type':'generated'}}, 'compose':{'services':{'web':{'image':self.image,'cpus':0.5,'mem_limit':'64m','ports':[{'target':8080,'published':'${http_port}','host_ip':'${PUBLIC_BIND}'}],'environment':{'TOKEN':'${token}'}}}},'entrypoints':[{'label':'访问','url':'http://${PUBLIC_HOST}:${http_port}/'}]}

    def tearDown(self):self.temp.cleanup()

    def package(self,doc=None,extra=None,invalid_signature=False):
        doc=doc or self.doc;raw=json.dumps(doc).encode();file=self.root/'manifest.json';file.write_bytes(raw)
        signature=self.root/'signature';subprocess.run(['openssl','dgst','-sha256','-sign',str(self.private),'-out',str(signature),str(file)],check=True,stdout=subprocess.DEVNULL)
        output=io.BytesIO()
        with tarfile.open(fileobj=output,mode='w:gz') as package:
            records={'installer.json':raw,'installer.sig':b'invalid' if invalid_signature else signature.read_bytes(),'images/test.tar':b'image-fixture'}
            if extra:records.update(extra)
            for name,data in records.items():
                entry=tarfile.TarInfo(name);entry.size=len(data);package.addfile(entry,io.BytesIO(data))
        return output.getvalue()

    def upload(self,data=None):
        data=data or self.package()
        with patch.object(agent.sys,'stdin',type('Input',(),{'buffer':io.BytesIO(data)})()):
            return self.installer.upload({'installation_id':'install-test','archive_bytes':len(data),'archive_sha256':hashlib.sha256(data).hexdigest()})

    def docker(self,args,**kwargs):
        if args[0]=='info':return json.dumps({'MemTotal':8*1024**3,'NCPU':4,'Architecture':'x86_64'})
        if args[:2]==['compose','version']:return '2.41.2'
        return ''

    def plan(self,metadata):
        with patch.object(self.a,'run',side_effect=self.docker):
            return self.installer.plan('install-test',{'bundle_id':metadata['bundle_id'],'parameters':{'http_port':18899,'token':'private-generated-token'},'public_host':'192.0.2.10'})

    def test_signed_bundle_can_be_verified_and_planned_without_starting_containers(self):
        metadata=self.upload();result=self.plan(metadata)
        self.assertTrue(result['ready']);self.assertEqual('http://192.0.2.10:18899/',result['entrypoints'][0]['url'])
        self.assertNotIn('private-generated-token',json.dumps(result));self.assertNotIn('token',result['configuration'])
        self.assertTrue((self.a.root/'installations/install-test/plan.json').is_file())

    def test_untrusted_signature_is_rejected(self):
        with self.assertRaisesRegex(agent.AgentError,'SIGNATURE_NOT_TRUSTED'):self.upload(self.package(invalid_signature=True))

    def test_undeclared_files_and_traversal_are_rejected(self):
        with self.assertRaisesRegex(agent.AgentError,'UNDECLARED_FILES'):self.upload(self.package(extra={'unknown':b'x'}))
        with self.assertRaisesRegex(agent.AgentError,'UNSAFE_PATH'):self.upload(self.package(extra={'../outside':b'x'}))
        self.assertFalse((self.root/'outside').exists())

    def test_declared_files_must_match_their_checksums(self):
        doc=copy.deepcopy(self.doc);doc['files']['images/test.tar']='0'*64
        with self.assertRaisesRegex(agent.AgentError,'ASSET_CHECKSUM'):self.upload(self.package(doc))

    def test_product_and_capability_need_host_approval(self):
        doc=copy.deepcopy(self.doc);doc['capabilities']=['docker_socket']
        with self.assertRaisesRegex(agent.AgentError,'CAPABILITY_NOT_APPROVED'):self.upload(self.package(doc))
        doc=copy.deepcopy(self.doc);doc['product']='other-product'
        with self.assertRaisesRegex(agent.AgentError,'PRODUCT_NOT_APPROVED'):self.upload(self.package(doc))

    def test_package_cannot_mount_arbitrary_host_directories(self):
        doc=copy.deepcopy(self.doc);doc['compose']['services']['web']['volumes']=[{'type':'bind','source':'/etc','target':'/data'}]
        metadata=self.upload(self.package(doc))
        with self.assertRaisesRegex(agent.AgentError,'BIND_PATH'):self.plan(metadata)

    def test_network_allocation_accepts_docker_host_networks_without_ipam_and_avoids_used_ranges(self):
        doc=copy.deepcopy(self.doc);doc['network_allocations']={'CODING':{'offsets':[10,20]}}
        def docker(args,**kwargs):
            if args[:2]==['network','ls']:return 'host bridge'
            if args[:2]==['network','inspect']:return json.dumps([{'IPAM':{'Config':None}},{'IPAM':{'Config':[{'Subnet':'10.192.0.0/24'}]}}])
            return self.docker(args,**kwargs)
        with patch.object(self.a,'run',side_effect=docker):context=self.installer.intrinsic_values('install-test',self.a.root/'new',doc,'192.0.2.10')
        self.assertNotEqual('10.192.0.0/24',context['CODING_SUBNET']);self.assertTrue(context['CODING_IP_20'].endswith('.20'))

    def test_busy_port_produces_a_failed_preflight_without_deployment(self):
        with socket.socket() as reserved:
            reserved.bind(('127.0.0.1',18899));metadata=self.upload();result=self.plan(metadata)
        self.assertFalse(result['ready']);self.assertTrue(any(item['code']=='PORT' and not item['passed'] for item in result['checks']))

    def test_docker_port_allocation_and_existing_project_are_also_checked(self):
        metadata=self.upload()
        def docker(args,**kwargs):
            if args[:2]==['container','ls']:return json.dumps('0.0.0.0:18899->8080/tcp')
            if args[0]=='ps':return 'existing-container'
            return self.docker(args,**kwargs)
        with patch.object(self.a,'run',side_effect=docker):
            result=self.installer.plan('install-test',{'bundle_id':metadata['bundle_id'],'parameters':{'http_port':18899,'token':'token'},'public_host':'localhost'})
        self.assertFalse(result['ready']);self.assertTrue(any(item['code']=='PROJECT_AVAILABLE' and not item['passed'] for item in result['checks']))

    def test_changed_plan_and_expiry_are_rejected_before_importing_images(self):
        metadata=self.upload();result=self.plan(metadata)
        with self.assertRaisesRegex(agent.AgentError,'PLAN_EXPIRED_OR_CHANGED'):self.installer.apply('install-test',{'plan_id':'0'*64})
        file=self.a.root/'installations/install-test/plan.json';doc=json.loads(file.read_text());doc['created_at']=0;file.write_text(json.dumps(doc))
        with self.assertRaisesRegex(agent.AgentError,'PLAN_EXPIRED_OR_CHANGED'):self.installer.apply('install-test',{'plan_id':result['plan_id']})

    def test_failed_health_check_stops_only_new_project_and_preserves_its_data(self):
        metadata=self.upload();plan=self.plan(metadata)
        destination=self.a.root/'installations/install-test';data=destination/'customer-data';data.write_bytes(b'must survive')
        agent.atomic_json(self.a.root/'imports'/(self.image[7:]+'.json'),{'image':self.image})
        with patch.object(self.a,'run',side_effect=self.docker),patch.object(self.a,'import_archive',return_value={'images':[{'image':self.image}]}),patch.object(StackOperations,'compose') as compose,patch.object(StackOperations,'wait_healthy',side_effect=agent.AgentError('STACK_HEALTH_DEADLINE')),patch.object(StackOperations,'containers',return_value={'web':{'State':{'Running':False}}}):
            with self.assertRaisesRegex(agent.AgentError,'INSTALL_FAILED_DATA_RETAINED') as raised:self.installer.apply('install-test',{'plan_id':plan['plan_id']})
        self.assertEqual(b'must survive',data.read_bytes())
        self.assertEqual(['web'],raised.exception.details['failed_services'])
        self.assertEqual('STOPPED',StackOperations(self.a).state('install-test')['desired_state'])
        self.assertFalse((destination/'installed.json').exists())
        self.assertTrue(any(call.args[2:]==('stop',) for call in compose.call_args_list))
        self.assertTrue(all(call.args[0]['stack_id']=='install-test' for call in compose.call_args_list))
        self.assertFalse(any('down' in call.args[2:] for call in compose.call_args_list))


if __name__=='__main__':unittest.main()
